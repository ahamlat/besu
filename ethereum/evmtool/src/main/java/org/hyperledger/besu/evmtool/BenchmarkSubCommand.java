/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evmtool;

import static org.hyperledger.besu.evmtool.BenchmarkSubCommand.COMMAND_NAME;
import static picocli.CommandLine.ScopeType.INHERIT;
import static picocli.CommandLine.ScopeType.LOCAL;

import org.hyperledger.besu.evm.precompile.AbstractBLS12PrecompiledContract;
import org.hyperledger.besu.evm.precompile.AbstractPrecompiledContract;
import org.hyperledger.besu.evmtool.benchmarks.AltBN128Benchmark;
import org.hyperledger.besu.evmtool.benchmarks.BLS12Benchmark;
import org.hyperledger.besu.evmtool.benchmarks.BenchmarkConfig;
import org.hyperledger.besu.evmtool.benchmarks.BenchmarkExecutor;
import org.hyperledger.besu.evmtool.benchmarks.ECRecoverBenchmark;
import org.hyperledger.besu.evmtool.benchmarks.KZGPointEvalBenchmark;
import org.hyperledger.besu.evmtool.benchmarks.ModExpBenchmark;
import org.hyperledger.besu.evmtool.benchmarks.P256VerifyBenchmark;
import org.hyperledger.besu.evmtool.benchmarks.RipeMD160Benchmark;
import org.hyperledger.besu.evmtool.benchmarks.SHA256Benchmark;
import org.hyperledger.besu.util.BesuVersionUtils;
import org.hyperledger.besu.util.LogConfigurator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.management.OperatingSystemMXBean;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * This class represents the BenchmarkSubCommand. It is responsible for executing an Ethereum State
 * Test.
 */
@CommandLine.Command(
    name = COMMAND_NAME,
    description = "Execute an Ethereum State Test.",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class)
public class BenchmarkSubCommand implements Runnable {
  /**
   * The command name for the BenchmarkSubCommand. This constant is used as the name attribute in
   * the {@code CommandLine.Command} annotation.
   */
  public static final String COMMAND_NAME = "benchmark";

  /** Stream for where to write the output to. */
  private final PrintStream output;

  enum Benchmark {
    altBn128(AltBN128Benchmark::new),
    // blake2f
    EcRecover(ECRecoverBenchmark::new),
    ModExp(ModExpBenchmark::new),
    // bls12
    Bls12(BLS12Benchmark::new),
    p256Verify(P256VerifyBenchmark::new),
    sha256(SHA256Benchmark::new),
    RipeMD(RipeMD160Benchmark::new),
    kzgPointEval(KZGPointEvalBenchmark::new);

    private final BenchmarkExecutor.Builder executorBuilder;

    Benchmark(final BenchmarkExecutor.Builder executorBuilder) {
      this.executorBuilder = executorBuilder;
    }
  }

  @Option(
      names = {"--native"},
      description = "Use the native libraries.",
      scope = INHERIT,
      negatable = true)
  Boolean nativeCode = false;

  @Option(
      names = {"--use-precompile-cache"},
      description = "Benchmark using precompile caching.",
      scope = INHERIT,
      negatable = true)
  Boolean enablePrecompileCache = false;

  @Option(
      names = {"--async-profiler"},
      description =
          "Benchmark using async profiler. No profiler command means profiling disabled. '%%%%test-case' in the"
              + " file name expands to the test for which the profiler ran,"
              + "e.g. \"start,jfr,event=cpu,file=/tmp/%%%%test-case-%%p.jfr\".",
      scope = LOCAL)
  Optional<String> asyncProfilerOptions = Optional.empty();

  @Option(
      names = {"--pattern"},
      description =
          "Only tests cases with this pattern will be run, e.g. --pattern \"guido-3.*\". Default runs all test cases.",
      scope = LOCAL)
  Optional<String> testCasePattern = Optional.empty();

  @Option(
      names = {"--exec-iterations"},
      description =
          "Number of iterations that the benchmark should run (measurement) for, regardless of how long it takes.",
      scope = LOCAL)
  Optional<Integer> execIterations = Optional.empty();

  @Option(
      names = {"--exec-time"},
      description =
          "Run the maximum number of iterations during execution (measurement) within the given period. Time is in seconds.",
      scope = LOCAL)
  Optional<Integer> execTime = Optional.empty();

  @Option(
      names = {"--warm-iterations"},
      description =
          "Number of iterations that the benchmark should warm up for, regardless of how long it takes.",
      scope = LOCAL)
  Optional<Integer> warmIterations = Optional.empty();

  @Option(
      names = {"--warm-time"},
      description =
          "Run the maximum number of iterations during warmup within the given period. Time is in seconds.",
      scope = LOCAL)
  Optional<Integer> warmTime = Optional.empty();

  @Option(
      names = {"--attempt-cache-bust"},
      description =
          "Run each test case within each warmup and exec iteration. This attempts to warm the code without warming the data, i.e. avoid warming CPU caches. Benchmark must have sufficient number and variety of test cases to be effective. --warm-time, --exec-time and --async-profiler are ignored.",
      scope = LOCAL,
      negatable = true)
  Boolean attemptCacheBust = false;

  @Parameters(description = "One or more of ${COMPLETION-CANDIDATES}.")
  EnumSet<Benchmark> benchmarks = EnumSet.noneOf(Benchmark.class);

  @ParentCommand EvmToolCommand parentCommand;

  /** Default constructor for the BenchmarkSubCommand class. This is required by PicoCLI. */
  public BenchmarkSubCommand() {
    // PicoCLI requires this
    this(System.out);
  }

  /**
   * Constructs a new BenchmarkSubCommand with the given output stream.
   *
   * @param output the output stream to be used
   */
  public BenchmarkSubCommand(final PrintStream output) {
    this.output = output;
  }

  @Override
  public void run() {
    LogConfigurator.setLevel("", "DEBUG");
    output.println(BesuVersionUtils.version());
    AbstractPrecompiledContract.setPrecompileCaching(enablePrecompileCache);
    AbstractBLS12PrecompiledContract.setPrecompileCaching(enablePrecompileCache);
    var benchmarksToRun = benchmarks.isEmpty() ? EnumSet.allOf(Benchmark.class) : benchmarks;
    final BenchmarkConfig benchmarkConfig =
        new BenchmarkConfig(
            nativeCode,
            enablePrecompileCache,
            asyncProfilerOptions,
            testCasePattern,
            execIterations,
            execTime,
            warmIterations,
            warmTime,
            attemptCacheBust);
    for (var benchmark : benchmarksToRun) {
      output.println("\nBenchmarks for " + benchmark + " on fork " + parentCommand.getFork());
      BenchmarkExecutor executor = benchmark.executorBuilder.create(output, benchmarkConfig);
      if (executor.isPrecompile()) {
        BenchmarkExecutor.logPrecompileDerivedGasNotice(output);
      }
      executor.runBenchmark(nativeCode, parentCommand.getFork());
    }
    logSystemInfo(output);
  }

  private static void logSystemInfo(final PrintStream output) {
    output.println(
        "\n****************************** Hardware Specs ******************************");
    output.println("*");
    final HardwareSummary hardwareSummary = HardwareSummary.detect();
    output.println("* OS: " + hardwareSummary.osDescription());
    output.println(
        "* Processor: " + hardwareSummary.processorName().orElse("Unavailable"));
    output.println(
        "* Microarchitecture: "
            + hardwareSummary.microarchitecture().orElse("Unavailable"));
    output.println(
        "* Physical CPU packages: "
            + (hardwareSummary.physicalPackages().isPresent()
                ? Integer.toString(hardwareSummary.physicalPackages().getAsInt())
                : "Unavailable"));
    output.println(
        "* Physical CPU cores: "
            + (hardwareSummary.physicalCores().isPresent()
                ? Integer.toString(hardwareSummary.physicalCores().getAsInt())
                : "Unavailable"));
    output.println("* Logical CPU cores: " + hardwareSummary.logicalCores());
    output.println(
        "* Average Max Frequency per core: "
            + (hardwareSummary.averageMaxFrequencyMhz().isPresent()
                ? hardwareSummary.averageMaxFrequencyMhz().getAsLong() + " MHz"
                : "Unavailable"));
    output.println(
        "* Memory Total: "
            + (hardwareSummary.totalMemoryBytes().isPresent()
                ? hardwareSummary.totalMemoryBytes().getAsLong() / 1_000_000_000 + " GB"
                : "Unavailable"));
  }

  private record HardwareSummary(
      String osDescription,
      Optional<String> processorName,
      Optional<String> microarchitecture,
      OptionalInt physicalPackages,
      OptionalInt physicalCores,
      int logicalCores,
      OptionalLong averageMaxFrequencyMhz,
      OptionalLong totalMemoryBytes) {

    private static HardwareSummary detect() {
      final String osName = System.getProperty("os.name", "Unknown");
      final String osVersion = System.getProperty("os.version", "");
      final String osArch = System.getProperty("os.arch", "");
      final String osDescription =
          (osName + " " + osVersion).trim()
              + (osArch.isBlank() ? "" : " (" + osArch.trim() + ")");

      final int logicalCores = Runtime.getRuntime().availableProcessors();

      OptionalLong totalMemoryBytes = OptionalLong.empty();
      final OperatingSystemMXBean osBean =
          ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
      if (osBean != null) {
        try {
          final long total = osBean.getTotalPhysicalMemorySize();
          if (total > 0) {
            totalMemoryBytes = OptionalLong.of(total);
          }
        } catch (final UnsupportedOperationException ignored) {
          // some JVMs may not support this call
        }
      }

      Optional<String> processorName = Optional.empty();
      Optional<String> microarchitecture = Optional.empty();
      OptionalInt physicalPackages = OptionalInt.empty();
      OptionalInt physicalCores = OptionalInt.empty();
      OptionalLong averageMaxFrequencyMhz = OptionalLong.empty();

      final String osNameLower = osName.toLowerCase(Locale.ROOT);
      if (osNameLower.contains("linux")) {
        final LinuxCpuInfo linuxCpuInfo = LinuxCpuInfo.detect();
        processorName = linuxCpuInfo.processorName();
        physicalPackages = linuxCpuInfo.physicalPackages();
        physicalCores = linuxCpuInfo.physicalCores();
        averageMaxFrequencyMhz = linuxCpuInfo.averageMaxFrequencyMhz();
      } else if (osNameLower.contains("mac")) {
        processorName = detectMacProcessorName();
      } else if (osNameLower.contains("windows")) {
        processorName = detectWindowsProcessorName();
      }

      if (processorName.isEmpty()) {
        processorName =
            Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER")).filter(s -> !s.isBlank());
      }

      return new HardwareSummary(
          osDescription,
          processorName.map(String::trim),
          microarchitecture,
          physicalPackages,
          physicalCores,
          logicalCores,
          averageMaxFrequencyMhz,
          totalMemoryBytes);
    }
  }

  private record LinuxCpuInfo(
      Optional<String> processorName,
      OptionalInt physicalPackages,
      OptionalInt physicalCores,
      OptionalLong averageMaxFrequencyMhz) {

    private static LinuxCpuInfo detect() {
      final Path cpuInfoPath = Path.of("/proc/cpuinfo");
      if (!Files.isReadable(cpuInfoPath)) {
        return new LinuxCpuInfo(
            Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty());
      }

      final Set<String> packageIds = new HashSet<>();
      final Set<String> physicalCoreIds = new HashSet<>();
      final Map<String, Integer> coresPerPackage = new HashMap<>();
      final AtomicReference<String> processorName = new AtomicReference<>();
      double totalObservedFrequencyMhz = 0.0D;
      int frequencySamples = 0;

      try (BufferedReader reader =
          Files.newBufferedReader(cpuInfoPath, StandardCharsets.UTF_8)) {
        final Map<String, String> block = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            final double observedFrequency =
                processBlock(
                    block, packageIds, physicalCoreIds, coresPerPackage, processorName);
            if (!Double.isNaN(observedFrequency)) {
              totalObservedFrequencyMhz += observedFrequency;
              frequencySamples++;
            }
          } else {
            final int separator = line.indexOf(':');
            if (separator > -1) {
              final String key = line.substring(0, separator).trim();
              final String value = line.substring(separator + 1).trim();
              block.put(key, value);
            }
          }
        }
        final double observedFrequency =
            processBlock(
                block, packageIds, physicalCoreIds, coresPerPackage, processorName);
        if (!Double.isNaN(observedFrequency)) {
          totalObservedFrequencyMhz += observedFrequency;
          frequencySamples++;
        }
      } catch (final IOException ignored) {
        return new LinuxCpuInfo(
            Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty());
      }

      final OptionalInt packages =
          packageIds.isEmpty() ? OptionalInt.empty() : OptionalInt.of(packageIds.size());

      OptionalInt cores = OptionalInt.empty();
      if (!physicalCoreIds.isEmpty()) {
        cores = OptionalInt.of(physicalCoreIds.size());
      } else if (!coresPerPackage.isEmpty()) {
        final int totalCores =
            coresPerPackage.values().stream().mapToInt(Integer::intValue).sum();
        if (totalCores > 0) {
          cores = OptionalInt.of(totalCores);
        }
      }

      OptionalLong averageFrequency =
          frequencySamples == 0
              ? OptionalLong.empty()
              : OptionalLong.of(Math.round(totalObservedFrequencyMhz / frequencySamples));

      return new LinuxCpuInfo(
          Optional.ofNullable(processorName.get()).filter(name -> !name.isBlank()),
          packages,
          cores,
          averageFrequency);
    }

    private static double processBlock(
        final Map<String, String> block,
        final Set<String> packageIds,
        final Set<String> physicalCoreIds,
        final Map<String, Integer> coresPerPackage,
        final AtomicReference<String> processorName) {
      if (block.isEmpty()) {
        return Double.NaN;
      }
      if (processorName.get() == null) {
        String modelName = block.get("model name");
        if (modelName == null || modelName.isBlank()) {
          modelName = block.get("Hardware");
        }
        if (modelName != null && !modelName.isBlank()) {
          processorName.set(modelName.trim());
        }
      }
      final String physicalId = block.get("physical id");
      if (physicalId != null && !physicalId.isBlank()) {
        packageIds.add(physicalId);
      }
      final String coreId = block.get("core id");
      if (physicalId != null && coreId != null && !coreId.isBlank()) {
        physicalCoreIds.add(physicalId + ":" + coreId);
      }
      final String coresCount = block.get("cpu cores");
      if (physicalId != null && coresCount != null && !coresCount.isBlank()) {
        try {
          coresPerPackage.putIfAbsent(physicalId, Integer.parseInt(coresCount.trim()));
        } catch (final NumberFormatException ignored) {
          // ignore unparsable values
        }
      }
      final String cpuMhz = block.get("cpu MHz");
      double observedFrequency = Double.NaN;
      if (cpuMhz != null && !cpuMhz.isBlank()) {
        try {
          observedFrequency = Double.parseDouble(cpuMhz.trim());
        } catch (final NumberFormatException ignored) {
          // ignore unparsable values
        }
      }
      block.clear();
      return observedFrequency;
    }
  }

  private static Optional<String> detectMacProcessorName() {
    final ProcessBuilder builder =
        new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string");
    try {
      final Process process = builder.start();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        final String line = reader.readLine();
        if (line != null && !line.isBlank()) {
          return Optional.of(line.trim());
        }
      } finally {
        process.destroy();
      }
    } catch (final IOException ignored) {
      // ignore inability to run sysctl
    } catch (final SecurityException ignored) {
      // insufficient privileges to execute sysctl
    }
    return Optional.empty();
  }

  private static Optional<String> detectWindowsProcessorName() {
    return Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER")).filter(s -> !s.isBlank());
  }
}

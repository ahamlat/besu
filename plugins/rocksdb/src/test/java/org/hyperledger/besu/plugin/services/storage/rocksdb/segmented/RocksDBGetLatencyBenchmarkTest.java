/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBConfigurationBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.FlushOptions;

/**
 * Latency benchmark for the RocksDB point-lookup path, using a database shaped like Besu's
 * ACCOUNT_STORAGE_STORAGE segment (64-byte keys = account hash + slot hash, 32-byte values).
 *
 * <p>Compares the tuned state segment (memtable bloom + data-block hash index) against the default
 * configuration, for cache-hit gets, missing-key gets and batched same-account reads (multiGet vs
 * looped get). Results are printed to stdout (visible in the Gradle test XML report); assertions
 * only ensure correctness, not speed, so the test never flakes on slow machines.
 *
 * <p>Manual benchmark: remove the {@code @Disabled} annotation to run. Takes ~2 minutes.
 */
@Disabled("manual benchmark, not part of CI")
class RocksDBGetLatencyBenchmarkTest {

  private static final int ACCOUNTS = 2_000;
  private static final int SLOTS_PER_ACCOUNT = 250; // 500k flushed entries
  private static final int MEMTABLE_ENTRIES = 100_000; // left unflushed on purpose
  private static final int READS = 200_000;
  private static final int BATCH_SIZE = 64;
  private static final int BATCHES = 2_000;

  /** Named like the real segment so it picks up the point-lookup tuning. */
  private enum BenchSegment implements SegmentIdentifier {
    DEFAULT("default".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
    ACCOUNT_STORAGE_STORAGE(new byte[] {8}), // tuned path
    BASELINE_STORAGE(new byte[] {9}); // default path

    private final byte[] id;

    BenchSegment(final byte[] id) {
      this.id = id;
    }

    @Override
    public String getName() {
      return name();
    }

    @Override
    public byte[] getId() {
      return id;
    }

    @Override
    public boolean containsStaticData() {
      return false;
    }

    @Override
    public boolean isEligibleToHighSpecFlag() {
      return true;
    }
  }

  @TempDir Path tempDir;

  @Test
  void benchmarkPointLookups() throws Exception {
    for (final BenchSegment segment :
        List.of(BenchSegment.ACCOUNT_STORAGE_STORAGE, BenchSegment.BASELINE_STORAGE)) {
      try (final OptimisticRocksDBColumnarKeyValueStorage store =
          new OptimisticRocksDBColumnarKeyValueStorage(
              new RocksDBConfigurationBuilder()
                  .databaseDir(tempDir.resolve(segment.getName()))
                  .build(),
              List.of(BenchSegment.DEFAULT, segment),
              List.of(),
              new NoOpMetricsSystem(),
              RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS)) {
        runScenario(segment, store);
      }
    }
  }

  private void runScenario(
      final BenchSegment segment, final OptimisticRocksDBColumnarKeyValueStorage store)
      throws Exception {
    System.out.println("=== " + segment.getName() + " ===");

    // fill: ACCOUNTS x SLOTS_PER_ACCOUNT flushed to SSTs, then MEMTABLE_ENTRIES kept in memtable
    long start = System.currentTimeMillis();
    fill(store, segment, 0, ACCOUNTS * SLOTS_PER_ACCOUNT);
    flush(store);
    fill(store, segment, ACCOUNTS * SLOTS_PER_ACCOUNT, MEMTABLE_ENTRIES);
    System.out.printf(
        "fill: %,d entries (%,d in memtable) in %,d ms%n",
        ACCOUNTS * SLOTS_PER_ACCOUNT + MEMTABLE_ENTRIES,
        MEMTABLE_ENTRIES,
        System.currentTimeMillis() - start);

    // warm-up pass so both scenarios measure a warm block cache (the CPU path, not disk)
    randomGets(store, segment, READS, true, false);

    // several rounds so machine noise is visible and comparisons between configs are trustworthy
    for (int round = 1; round <= 3; round++) {
      report("get hit  r" + round, randomGets(store, segment, READS, true, true));
      report("get miss r" + round, randomGets(store, segment, READS, false, true));

      // batched same-account reads: multiGet vs looped get on identical keys
      final long multiGetNs = batchedReads(store, segment, true);
      final long loopedNs = batchedReads(store, segment, false);
      System.out.printf(
          "batch %d slots r%d: multiGet %,d ns/key, looped get %,d ns/key (%.2fx)%n",
          BATCH_SIZE,
          round,
          multiGetNs / (BATCHES * BATCH_SIZE),
          loopedNs / (BATCHES * BATCH_SIZE),
          (double) loopedNs / multiGetNs);
    }
    System.out.println();
  }

  private void fill(
      final OptimisticRocksDBColumnarKeyValueStorage store,
      final BenchSegment segment,
      final int firstIndex,
      final int count) {
    final int batchSize = 10_000;
    for (int done = 0; done < count; done += batchSize) {
      final SegmentedKeyValueStorageTransaction tx = store.startTransaction();
      for (int i = done; i < Math.min(done + batchSize, count); i++) {
        tx.put(segment, key(firstIndex + i), value(firstIndex + i));
      }
      tx.commit();
    }
  }

  private void flush(final OptimisticRocksDBColumnarKeyValueStorage store) throws Exception {
    try (final FlushOptions flushOptions = new FlushOptions().setWaitForFlush(true)) {
      for (final ColumnFamilyHandle handle : store.columnHandles) {
        store.getDB().flush(flushOptions, handle);
        store.getDB().compactRange(handle);
      }
    }
  }

  private long[] randomGets(
      final OptimisticRocksDBColumnarKeyValueStorage store,
      final BenchSegment segment,
      final int reads,
      final boolean existing,
      final boolean record) {
    final Random random = new Random(existing ? 42 : 4242);
    final int keySpace = ACCOUNTS * SLOTS_PER_ACCOUNT + MEMTABLE_ENTRIES;
    final long[] samples = record ? new long[reads] : null;
    for (int i = 0; i < reads; i++) {
      final int index = random.nextInt(keySpace);
      final byte[] key = existing ? key(index) : missingKey(index);
      final long t0 = System.nanoTime();
      final Optional<byte[]> result = store.get(segment, key);
      final long elapsed = System.nanoTime() - t0;
      if (record) {
        samples[i] = elapsed;
      }
      assertThat(result.isPresent()).isEqualTo(existing);
    }
    return samples;
  }

  private long batchedReads(
      final OptimisticRocksDBColumnarKeyValueStorage store,
      final BenchSegment segment,
      final boolean useMultiGet) {
    final Random random = new Random(7);
    long total = 0;
    for (int batch = 0; batch < BATCHES; batch++) {
      // BATCH_SIZE consecutive slots of one account: shared 32-byte key prefix, like Besu
      final int account = random.nextInt(ACCOUNTS);
      final int firstSlot = random.nextInt(SLOTS_PER_ACCOUNT - BATCH_SIZE);
      final List<byte[]> keys = new ArrayList<>(BATCH_SIZE);
      for (int slot = 0; slot < BATCH_SIZE; slot++) {
        keys.add(key(account * SLOTS_PER_ACCOUNT + firstSlot + slot));
      }
      final long t0 = System.nanoTime();
      if (useMultiGet) {
        final List<Optional<byte[]>> values = store.multiGet(segment, keys);
        assertThat(values).hasSize(BATCH_SIZE);
      } else {
        for (final byte[] key : keys) {
          assertThat(store.get(segment, key)).isPresent();
        }
      }
      total += System.nanoTime() - t0;
    }
    return total;
  }

  private static void report(final String label, final long[] samples) {
    final long[] sorted = samples.clone();
    Arrays.sort(sorted);
    long sum = 0;
    for (final long sample : sorted) {
      sum += sample;
    }
    System.out.printf(
        "%s: mean %,6d ns  p50 %,6d ns  p99 %,6d ns  (%,d reads)%n",
        label,
        sum / sorted.length,
        sorted[sorted.length / 2],
        sorted[(int) (sorted.length * 0.99)],
        sorted.length);
  }

  /** 64-byte key: 32-byte account hash + 32-byte slot hash, deterministic per index. */
  private static byte[] key(final int index) {
    final byte[] key = new byte[64];
    fillDeterministic(key, 0, index / SLOTS_PER_ACCOUNT);
    fillDeterministic(key, 32, 0x7FFF_0000L + index);
    return key;
  }

  /** Same account prefixes as real keys but a slot hash that was never written. */
  private static byte[] missingKey(final int index) {
    final byte[] key = new byte[64];
    fillDeterministic(key, 0, index / SLOTS_PER_ACCOUNT);
    fillDeterministic(key, 32, 0x5EED_0000_0000L + index);
    return key;
  }

  private static byte[] value(final int index) {
    final byte[] value = new byte[32];
    fillDeterministic(value, 0, 0x0BAD_CAFEL + index);
    return value;
  }

  private static void fillDeterministic(final byte[] target, final int offset, final long seed) {
    final byte[] chunk = new byte[32];
    new Random(seed).nextBytes(chunk);
    System.arraycopy(chunk, 0, target, offset, 32);
  }
}

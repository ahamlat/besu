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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized, typed thread pools for block processing.
 *
 * <p>Historically the parallel block-processing path created several independent, statically sized
 * pools (one per feature): a fixed {@code NCPU} pool for transaction execution, a {@code 2*NCPU}
 * pool for BAL prefetch, and the JVM-wide {@link java.util.concurrent.ForkJoinPool#commonPool()} for
 * the asynchronous BAL state-root computation and for {@code parallelStream} storage updates. On a
 * machine with {@code N} cores this could leave well over {@code 5*N} platform threads runnable at
 * once, all contending for the same cores while doing either CPU-bound (EVM/keccak/trie) or
 * native-blocking (RocksDB JNI) work.
 *
 * <p>This holder replaces that "one pool per feature" model with a small number of pools typed by
 * the <em>nature</em> of the work, and gives the latency-critical state-root computation its own
 * pool instead of sharing the common pool:
 *
 * <ul>
 *   <li>{@link #cpuExecutor()} &mdash; bounded to the number of cores, for CPU-bound transaction
 *       execution. Oversubscribing cores with CPU work only adds scheduler churn, so this is kept at
 *       {@code NCPU}.
 *   <li>{@link #ioExecutor()} &mdash; sized to the storage device's useful read concurrency (not the
 *       core count), for the native-blocking RocksDB prefetch/read fan-out. This is intentionally
 *       separate from the CPU pool so best-effort prefetch can never steal a transaction-execution
 *       thread, and runs at a slightly lower priority because it is droppable work.
 *   <li>{@link #stateRootExecutor()} &mdash; a small, dedicated, high-priority pool for the
 *       asynchronous BAL state-root computation that runs ahead of {@code persist()} on the block's
 *       critical path. Keeping it off the common pool guarantees it is never starved by unrelated
 *       {@code parallelStream} work elsewhere in the JVM.
 * </ul>
 *
 * <p>All pool sizes can be overridden at startup via system properties (so they can be tuned/
 * benchmarked without recompiling):
 *
 * <ul>
 *   <li>{@code besu.block.cpuThreads} (default: number of available processors)
 *   <li>{@code besu.block.ioThreads} (default: 2 &times; available processors)
 *   <li>{@code besu.block.stateRootThreads} (default: 2)
 * </ul>
 *
 * <p>All threads are daemon threads so they never block JVM shutdown.
 */
public final class BlockProcessingExecutors {

  private static final int NCPU = Runtime.getRuntime().availableProcessors();

  private static final int CPU_THREADS = intProperty("besu.block.cpuThreads", NCPU);
  private static final int IO_THREADS = intProperty("besu.block.ioThreads", NCPU * 2);
  private static final int STATE_ROOT_THREADS = intProperty("besu.block.stateRootThreads", 2);

  /**
   * CPU-bound work: parallel transaction execution (EVM, keccak, RLP). Bounded to the core count and
   * run at normal priority.
   */
  private static final ExecutorService CPU_EXECUTOR =
      Executors.newFixedThreadPool(
          CPU_THREADS, namedDaemonThreadFactory("besu-block-cpu", Thread.NORM_PRIORITY + 1));

  /**
   * IO-bound work: best-effort, native-blocking RocksDB prefetch/reads. Sized to device read
   * concurrency rather than core count, and run at a slightly lower priority since prefetch is
   * droppable.
   */
  private static final ExecutorService IO_EXECUTOR =
      Executors.newFixedThreadPool(
          IO_THREADS, namedDaemonThreadFactory("besu-block-io", Thread.NORM_PRIORITY));

  /**
   * Critical-path work: the asynchronous BAL state-root computation. Small dedicated pool at higher
   * priority, isolated from the JVM common pool.
   */
  private static final ExecutorService STATE_ROOT_EXECUTOR =
      Executors.newFixedThreadPool(
          STATE_ROOT_THREADS,
          namedDaemonThreadFactory("besu-block-stateroot", Thread.NORM_PRIORITY - 1));

  private BlockProcessingExecutors() {}

  /**
   * Pool for CPU-bound parallel transaction execution.
   *
   * @return the CPU executor
   */
  public static ExecutorService cpuExecutor() {
    return CPU_EXECUTOR;
  }

  /**
   * Pool for native-blocking, best-effort storage prefetch/reads.
   *
   * @return the IO executor
   */
  public static ExecutorService ioExecutor() {
    return IO_EXECUTOR;
  }

  /**
   * Dedicated, high-priority pool for the critical-path asynchronous BAL state-root computation.
   *
   * @return the state-root executor
   */
  public static ExecutorService stateRootExecutor() {
    return STATE_ROOT_EXECUTOR;
  }

  private static int intProperty(final String key, final int defaultValue) {
    final String raw = System.getProperty(key);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      final int parsed = Integer.parseInt(raw.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (final NumberFormatException e) {
      return defaultValue;
    }
  }

  // The real prioritization between these workloads comes from pool isolation and sizing (a
  // best-effort prefetch can never occupy a CPU/state-root thread). The JVM thread-priority hint
  // below is a secondary, OS-advisory nudge; Error Prone discourages it because most schedulers
  // largely ignore it, but it is harmless and kept here to express intent.
  @SuppressWarnings("ThreadPriorityCheck")
  private static ThreadFactory namedDaemonThreadFactory(final String prefix, final int priority) {
    final int clampedPriority =
        Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority));
    final AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      final Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
      thread.setDaemon(true);
      thread.setPriority(clampedPriority);
      return thread;
    };
  }
}

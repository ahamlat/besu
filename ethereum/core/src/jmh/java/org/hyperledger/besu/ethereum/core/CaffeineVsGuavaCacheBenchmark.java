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
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.util.CacheMaintenanceExecutor;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.CacheBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark comparing Caffeine vs Guava cache performance for patterns used in Besu.
 *
 * <p>Run with: ./gradlew :ethereum:core:jmh -Pincludes=CaffeineVsGuavaCacheBenchmark
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
public class CaffeineVsGuavaCacheBenchmark {

  @Param({"1000", "10000", "100000"})
  private int cacheSize;

  private Cache<Hash, Bytes> caffeineCache;
  private Cache<Hash, Bytes> caffeineWithExecutorCache;
  private com.google.common.cache.Cache<Hash, Bytes> guavaCache;

  private Hash[] preloadedKeys;
  private Bytes[] preloadedValues;
  private Hash[] missKeys;

  private static final int KEY_COUNT = 200_000;
  private static final int MISS_KEY_COUNT = 1_000;

  @Setup
  public void setUp() {
    caffeineCache = Caffeine.newBuilder().maximumSize(cacheSize).build();
    caffeineWithExecutorCache =
        Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .executor(CacheMaintenanceExecutor.getInstance())
            .build();
    guavaCache = CacheBuilder.newBuilder().maximumSize(cacheSize).build();

    preloadedKeys = new Hash[KEY_COUNT];
    preloadedValues = new Bytes[KEY_COUNT];
    for (int i = 0; i < KEY_COUNT; i++) {
      preloadedKeys[i] = Hash.hash(Bytes.ofUnsignedInt(i));
      preloadedValues[i] = preloadedKeys[i].getBytes();
    }

    for (int i = 0; i < KEY_COUNT; i++) {
      caffeineCache.put(preloadedKeys[i], preloadedValues[i]);
      caffeineWithExecutorCache.put(preloadedKeys[i], preloadedValues[i]);
      guavaCache.put(preloadedKeys[i], preloadedValues[i]);
    }
    caffeineCache.cleanUp();
    caffeineWithExecutorCache.cleanUp();

    missKeys = new Hash[MISS_KEY_COUNT];
    for (int i = 0; i < MISS_KEY_COUNT; i++) {
      missKeys[i] = Hash.hash(Bytes.ofUnsignedInt(KEY_COUNT + i));
    }
  }

  // ---- Single-threaded hit benchmarks ----

  @Benchmark
  public void caffeine_hit(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  public void caffeineWithExecutor_hit(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineWithExecutorCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  public void guava_hit(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(guavaCache.getIfPresent(preloadedKeys[i]));
    }
  }

  // ---- Single-threaded miss benchmarks ----

  @Benchmark
  public void caffeine_miss(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineCache.getIfPresent(missKeys[i % MISS_KEY_COUNT]));
    }
  }

  @Benchmark
  public void caffeineWithExecutor_miss(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineWithExecutorCache.getIfPresent(missKeys[i % MISS_KEY_COUNT]));
    }
  }

  @Benchmark
  public void guava_miss(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(guavaCache.getIfPresent(missKeys[i % MISS_KEY_COUNT]));
    }
  }

  // ---- Single-threaded put (with eviction) benchmarks ----

  @Benchmark
  public void caffeine_put(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      caffeineCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }

  @Benchmark
  public void caffeineWithExecutor_put(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      caffeineWithExecutorCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }

  @Benchmark
  public void guava_put(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      guavaCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }

  // ---- Single-threaded compute-if-absent (loading pattern) ----

  @Benchmark
  public void caffeine_computeIfAbsent(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      final int index = i;
      Hash key = preloadedKeys[index];
      bh.consume(caffeineCache.get(key, k -> preloadedValues[index]));
    }
  }

  @Benchmark
  public void caffeineWithExecutor_computeIfAbsent(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      final int index = i;
      Hash key = preloadedKeys[index];
      bh.consume(caffeineWithExecutorCache.get(key, k -> preloadedValues[index]));
    }
  }

  @Benchmark
  public void guava_computeIfAbsent(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      final int index = i;
      Hash key = preloadedKeys[index];
      try {
        bh.consume(guavaCache.get(key, () -> preloadedValues[index]));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  // ---- Multi-threaded concurrent read benchmarks ----

  @Benchmark
  @Group("caffeine_concurrent_read")
  @GroupThreads(4)
  public void caffeine_concurrentRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  @Group("caffeineWithExecutor_concurrent_read")
  @GroupThreads(4)
  public void caffeineWithExecutor_concurrentRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineWithExecutorCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  @Group("guava_concurrent_read")
  @GroupThreads(4)
  public void guava_concurrentRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(guavaCache.getIfPresent(preloadedKeys[i]));
    }
  }

  // ---- Multi-threaded mixed read/write benchmarks ----

  @Benchmark
  @Group("caffeine_mixed")
  @GroupThreads(3)
  public void caffeine_mixedRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  @Group("caffeine_mixed")
  @GroupThreads(1)
  public void caffeine_mixedWrite(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      caffeineCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }

  @Benchmark
  @Group("caffeineWithExecutor_mixed")
  @GroupThreads(3)
  public void caffeineWithExecutor_mixedRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(caffeineWithExecutorCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  @Group("caffeineWithExecutor_mixed")
  @GroupThreads(1)
  public void caffeineWithExecutor_mixedWrite(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      caffeineWithExecutorCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }

  @Benchmark
  @Group("guava_mixed")
  @GroupThreads(3)
  public void guava_mixedRead(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      bh.consume(guavaCache.getIfPresent(preloadedKeys[i]));
    }
  }

  @Benchmark
  @Group("guava_mixed")
  @GroupThreads(1)
  public void guava_mixedWrite(final Blackhole bh) {
    for (int i = 0; i < 100; i++) {
      int idx = (i + cacheSize) % KEY_COUNT;
      guavaCache.put(preloadedKeys[idx], preloadedValues[idx]);
    }
  }
}

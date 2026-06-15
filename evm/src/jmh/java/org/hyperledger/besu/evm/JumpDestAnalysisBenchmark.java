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
package org.hyperledger.besu.evm;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares the scalar {@link Code#calculateJumpDestBitMask()} against the SIMD {@link
 * Code#calculateJumpDestBitMaskVector()} across representative bytecode shapes.
 *
 * <p>The {@code calculate*} methods recompute on every call (no memoization), so a single {@link
 * Code} per case is reused. Run with: {@code ./gradlew :evm:jmh -Pincludes=JumpDestAnalysisBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JumpDestAnalysisBenchmark {

  /** EIP-170 maximum runtime code size. */
  private static final int MAX_CODE_SIZE = 0x6000;

  /**
   * Bytecode shape under test.
   *
   * <ul>
   *   <li>{@code ADVERSARIAL_24K} — the block-24,407,730 worst case: PUSH2/JUMP header then ~24 KB
   *       of contiguous JUMPDEST padding (every byte an opcode, no PUSH skipping, all uniform
   *       windows for the vector path).
   *   <li>{@code SOLIDITY_LIKE_8K} — PUSH-dense compiled-contract approximation: frequent
   *       PUSH1/PUSH2 with immediate data and scattered JUMPDESTs (mostly divergent windows).
   *   <li>{@code JUMPDEST_DENSE_8K} — long pushless opcode runs with occasional JUMPDESTs (mostly
   *       uniform windows, but not the degenerate all-0x5b case).
   *   <li>{@code RANDOM_8K} — uniformly random bytes.
   * </ul>
   */
  @Param({"ADVERSARIAL_24K", "SOLIDITY_LIKE_8K", "JUMPDEST_DENSE_8K", "RANDOM_8K"})
  public String caseName;

  private Code code;

  @Setup(Level.Trial)
  public void prepare() {
    code = new Code(Bytes.wrap(buildCode(caseName)));
  }

  private static byte[] buildCode(final String shape) {
    return switch (shape) {
      case "ADVERSARIAL_24K" -> adversarial();
      case "SOLIDITY_LIKE_8K" -> solidityLike(8192);
      case "JUMPDEST_DENSE_8K" -> jumpdestDense(8192);
      case "RANDOM_8K" -> random(8192);
      default -> throw new IllegalArgumentException("unknown case " + shape);
    };
  }

  private static byte[] adversarial() {
    final byte[] code = new byte[MAX_CODE_SIZE];
    Arrays.fill(code, (byte) 0x5b);
    code[0] = 0x61; // PUSH2
    code[1] = 0x5f;
    code[2] = (byte) 0xff;
    code[3] = 0x56; // JUMP
    return code;
  }

  /** Roughly Solidity-like: ~30% PUSH opcodes (with data), sparse JUMPDESTs, common opcodes. */
  private static byte[] solidityLike(final int length) {
    final Random random = new Random(1);
    final byte[] code = new byte[length];
    int i = 0;
    while (i < length) {
      final double r = random.nextDouble();
      if (r < 0.30 && i + 3 < length) {
        final int pushLen = 1 + random.nextInt(2); // PUSH1 or PUSH2
        code[i++] = (byte) (0x5f + pushLen);
        for (int k = 0; k < pushLen && i < length; k++) {
          code[i++] = (byte) random.nextInt(256); // immediate data (may include 0x5b)
        }
      } else if (r < 0.34) {
        code[i++] = 0x5b; // JUMPDEST
      } else {
        // common 1-byte opcodes: arithmetic/stack/memory, both below and above 0x5b
        final int[] ops = {0x01, 0x03, 0x10, 0x14, 0x15, 0x52, 0x54, 0x80, 0x90, 0x91, 0xf3};
        code[i++] = (byte) ops[random.nextInt(ops.length)];
      }
    }
    return code;
  }

  /** Long pushless opcode runs (DUP/SWAP/arithmetic) with ~1-in-20 JUMPDEST; rare PUSHes. */
  private static byte[] jumpdestDense(final int length) {
    final Random random = new Random(2);
    final byte[] code = new byte[length];
    int i = 0;
    while (i < length) {
      final double r = random.nextDouble();
      if (r < 0.05) {
        code[i++] = 0x5b; // JUMPDEST
      } else if (r < 0.07 && i + 2 < length) {
        code[i++] = 0x60; // PUSH1
        code[i++] = (byte) random.nextInt(256);
      } else {
        final int[] ops = {0x01, 0x02, 0x80, 0x81, 0x90, 0x91, 0x50, 0x56};
        code[i++] = (byte) ops[random.nextInt(ops.length)];
      }
    }
    return code;
  }

  private static byte[] random(final int length) {
    final byte[] code = new byte[length];
    new Random(3).nextBytes(code);
    return code;
  }

  @Benchmark
  public long[] scalar() {
    return code.calculateJumpDestBitMask();
  }

  @Benchmark
  public long[] vector() {
    return code.calculateJumpDestBitMaskVector();
  }
}

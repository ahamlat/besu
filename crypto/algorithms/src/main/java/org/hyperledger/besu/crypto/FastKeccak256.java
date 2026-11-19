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
package org.hyperledger.besu.crypto;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * High-throughput, zero-allocation Keccak-256 implementation using Keccak-f[1600] over 25 local
 * long lanes and direct VarHandle little-endian reads.
 *
 * <p>Optimized especially for the single-block inputs (&lt;= 135 bytes) that dominate Ethereum
 * execution: 20-byte addresses, 32-byte storage slots, 64-byte Solidity mapping keys, and
 * 96/128-byte nested mapping keys.
 */
public final class FastKeccak256 {

  private static final int RATE_BYTES = 136; // (1600 - 2*256) / 8 = 136 bytes = 17 lanes
  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  // Precomputed Keccak-256("") in big-endian limbs (matching EVM v2 stack layout)
  // 0xc5d2460186f7233c 927e7db2dcc703c0 e500b653ca82273b 7bfad8045d85a470
  private static final long EMPTY_U3 = 0xc5d2460186f7233cL;
  private static final long EMPTY_U2 = 0x927e7db2dcc703c0L;
  private static final long EMPTY_U1 = 0xe500b653ca82273bL;
  private static final long EMPTY_U0 = 0x7bfad8045d85a470L;

  private static final long[] RC = {
    0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
    0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
    0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
    0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
    0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
    0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
  };

  private static final ThreadLocal<long[]> SCRATCH_STATE =
      ThreadLocal.withInitial(() -> new long[25]);

  private FastKeccak256() {}

  /**
   * Computes Keccak-256 of {@code input[offset .. offset+length)} and writes the 256-bit hash
   * directly as 4 big-endian longs into {@code stack[stackSlotOffset .. stackSlotOffset+3]}.
   */
  public static void keccak256ToStack(
      final byte[] input,
      final int offset,
      final int length,
      final long[] stack,
      final int stackSlotOffset) {
    if (length == 0) {
      stack[stackSlotOffset] = EMPTY_U3;
      stack[stackSlotOffset + 1] = EMPTY_U2;
      stack[stackSlotOffset + 2] = EMPTY_U1;
      stack[stackSlotOffset + 3] = EMPTY_U0;
      return;
    }

    if (length == 64) {
      // Dedicated register-only fast path for 64-byte Solidity mapping keys (0 heap/array traffic)
      keccak64ToStack(input, offset, stack, stackSlotOffset);
      return;
    }
    if (length == 32) {
      keccak32ToStack(input, offset, stack, stackSlotOffset);
      return;
    }

    final long[] s = SCRATCH_STATE.get();
    absorbAndPermute(input, offset, length, s);
    stack[stackSlotOffset] = Long.reverseBytes(s[0]);
    stack[stackSlotOffset + 1] = Long.reverseBytes(s[1]);
    stack[stackSlotOffset + 2] = Long.reverseBytes(s[2]);
    stack[stackSlotOffset + 3] = Long.reverseBytes(s[3]);
  }

  /** Computes Keccak-256 of {@code input[offset .. offset+length)} into a fresh 32-byte array. */
  public static byte[] keccak256(final byte[] input, final int offset, final int length) {
    final byte[] out = new byte[32];
    if (length == 0) {
      LONG_LE.set(out, 0, Long.reverseBytes(EMPTY_U3));
      LONG_LE.set(out, 8, Long.reverseBytes(EMPTY_U2));
      LONG_LE.set(out, 16, Long.reverseBytes(EMPTY_U1));
      LONG_LE.set(out, 24, Long.reverseBytes(EMPTY_U0));
      return out;
    }
    final long[] s = SCRATCH_STATE.get();
    absorbAndPermute(input, offset, length, s);
    LONG_LE.set(out, 0, s[0]);
    LONG_LE.set(out, 8, s[1]);
    LONG_LE.set(out, 16, s[2]);
    LONG_LE.set(out, 24, s[3]);
    return out;
  }

  private static void keccak32ToStack(
      final byte[] in, final int off, final long[] stack, final int dst) {
    permuteSingleBlockToStack(
        (long) LONG_LE.get(in, off),
        (long) LONG_LE.get(in, off + 8),
        (long) LONG_LE.get(in, off + 16),
        (long) LONG_LE.get(in, off + 24),
        0x01L,
        0L,
        0L,
        0L,
        0L,
        stack,
        dst);
  }

  private static void keccak64ToStack(
      final byte[] in, final int off, final long[] stack, final int dst) {
    permuteSingleBlockToStack(
        (long) LONG_LE.get(in, off),
        (long) LONG_LE.get(in, off + 8),
        (long) LONG_LE.get(in, off + 16),
        (long) LONG_LE.get(in, off + 24),
        (long) LONG_LE.get(in, off + 32),
        (long) LONG_LE.get(in, off + 40),
        (long) LONG_LE.get(in, off + 48),
        (long) LONG_LE.get(in, off + 56),
        0x01L,
        stack,
        dst);
  }

  private static void permuteSingleBlockToStack(
      final long p00,
      final long p01,
      final long p02,
      final long p03,
      final long p04,
      final long p05,
      final long p06,
      final long p07,
      final long p08,
      final long[] stack,
      final int dst) {
    long a00 = p00, a01 = p01, a02 = p02, a03 = p03, a04 = p04;
    long a05 = p05, a06 = p06, a07 = p07, a08 = p08;
    long a09 = 0L, a10 = 0L, a11 = 0L, a12 = 0L, a13 = 0L, a14 = 0L, a15 = 0L;
    long a16 = 0x8000000000000000L;
    long a17 = 0L, a18 = 0L, a19 = 0L, a20 = 0L, a21 = 0L, a22 = 0L, a23 = 0L, a24 = 0L;

    for (int r = 0; r < 24; r++) {
      final long c0 = a00 ^ a05 ^ a10 ^ a15 ^ a20;
      final long c1 = a01 ^ a06 ^ a11 ^ a16 ^ a21;
      final long c2 = a02 ^ a07 ^ a12 ^ a17 ^ a22;
      final long c3 = a03 ^ a08 ^ a13 ^ a18 ^ a23;
      final long c4 = a04 ^ a09 ^ a14 ^ a19 ^ a24;

      final long d0 = c4 ^ Long.rotateLeft(c1, 1);
      final long d1 = c0 ^ Long.rotateLeft(c2, 1);
      final long d2 = c1 ^ Long.rotateLeft(c3, 1);
      final long d3 = c2 ^ Long.rotateLeft(c4, 1);
      final long d4 = c3 ^ Long.rotateLeft(c0, 1);

      a00 ^= d0;
      a05 ^= d0;
      a10 ^= d0;
      a15 ^= d0;
      a20 ^= d0;
      a01 ^= d1;
      a06 ^= d1;
      a11 ^= d1;
      a16 ^= d1;
      a21 ^= d1;
      a02 ^= d2;
      a07 ^= d2;
      a12 ^= d2;
      a17 ^= d2;
      a22 ^= d2;
      a03 ^= d3;
      a08 ^= d3;
      a13 ^= d3;
      a18 ^= d3;
      a23 ^= d3;
      a04 ^= d4;
      a09 ^= d4;
      a14 ^= d4;
      a19 ^= d4;
      a24 ^= d4;

      final long b00 = a00;
      final long b10 = Long.rotateLeft(a01, 1);
      final long b20 = Long.rotateLeft(a02, 62);
      final long b05 = Long.rotateLeft(a03, 28);
      final long b15 = Long.rotateLeft(a04, 27);

      final long b16 = Long.rotateLeft(a05, 36);
      final long b01 = Long.rotateLeft(a06, 44);
      final long b11 = Long.rotateLeft(a07, 6);
      final long b21 = Long.rotateLeft(a08, 55);
      final long b06 = Long.rotateLeft(a09, 20);

      final long b07 = Long.rotateLeft(a10, 3);
      final long b17 = Long.rotateLeft(a11, 10);
      final long b02 = Long.rotateLeft(a12, 43);
      final long b12 = Long.rotateLeft(a13, 25);
      final long b22 = Long.rotateLeft(a14, 39);

      final long b23 = Long.rotateLeft(a15, 41);
      final long b08 = Long.rotateLeft(a16, 45);
      final long b18 = Long.rotateLeft(a17, 15);
      final long b03 = Long.rotateLeft(a18, 21);
      final long b13 = Long.rotateLeft(a19, 8);

      final long b14 = Long.rotateLeft(a20, 18);
      final long b24 = Long.rotateLeft(a21, 2);
      final long b09 = Long.rotateLeft(a22, 61);
      final long b19 = Long.rotateLeft(a23, 56);
      final long b04 = Long.rotateLeft(a24, 14);

      a00 = b00 ^ (~b01 & b02) ^ RC[r];
      a01 = b01 ^ (~b02 & b03);
      a02 = b02 ^ (~b03 & b04);
      a03 = b03 ^ (~b04 & b00);
      a04 = b04 ^ (~b00 & b01);

      a05 = b05 ^ (~b06 & b07);
      a06 = b06 ^ (~b07 & b08);
      a07 = b07 ^ (~b08 & b09);
      a08 = b08 ^ (~b09 & b05);
      a09 = b09 ^ (~b05 & b06);

      a10 = b10 ^ (~b11 & b12);
      a11 = b11 ^ (~b12 & b13);
      a12 = b12 ^ (~b13 & b14);
      a13 = b13 ^ (~b14 & b10);
      a14 = b14 ^ (~b10 & b11);

      a15 = b15 ^ (~b16 & b17);
      a16 = b16 ^ (~b17 & b18);
      a17 = b17 ^ (~b18 & b19);
      a18 = b18 ^ (~b19 & b15);
      a19 = b19 ^ (~b15 & b16);

      a20 = b20 ^ (~b21 & b22);
      a21 = b21 ^ (~b22 & b23);
      a22 = b22 ^ (~b23 & b24);
      a23 = b23 ^ (~b24 & b20);
      a24 = b24 ^ (~b20 & b21);
    }

    stack[dst] = Long.reverseBytes(a00);
    stack[dst + 1] = Long.reverseBytes(a01);
    stack[dst + 2] = Long.reverseBytes(a02);
    stack[dst + 3] = Long.reverseBytes(a03);
  }

  private static void absorbAndPermute(
      final byte[] input, final int offset, final int length, final long[] s) {
    for (int i = 0; i < 25; i++) {
      s[i] = 0L;
    }
    int pos = offset;
    int rem = length;
    while (rem >= RATE_BYTES) {
      for (int i = 0; i < 17; i++) {
        s[i] ^= (long) LONG_LE.get(input, pos + (i << 3));
      }
      permute(s);
      pos += RATE_BYTES;
      rem -= RATE_BYTES;
    }

    // Absorb remaining full 8-byte lanes
    int lane = 0;
    while (rem >= 8) {
      s[lane++] ^= (long) LONG_LE.get(input, pos);
      pos += 8;
      rem -= 8;
    }

    // Absorb trailing bytes (< 8) + Keccak domain pad 0x01
    long tail = 0L;
    for (int b = 0; b < rem; b++) {
      tail |= (input[pos + b] & 0xFFL) << (b << 3);
    }
    tail |= 0x01L << (rem << 3);
    s[lane] ^= tail;
    // Final padding bit at byte 135 (MSB of lane 16)
    s[16] ^= 0x8000000000000000L;
    permute(s);
  }

  /** Fully scalarized Keccak-f[1600] permutation keeping all 25 lanes in local registers. */
  private static void permute(final long[] s) {
    long a00 = s[0], a01 = s[1], a02 = s[2], a03 = s[3], a04 = s[4];
    long a05 = s[5], a06 = s[6], a07 = s[7], a08 = s[8], a09 = s[9];
    long a10 = s[10], a11 = s[11], a12 = s[12], a13 = s[13], a14 = s[14];
    long a15 = s[15], a16 = s[16], a17 = s[17], a18 = s[18], a19 = s[19];
    long a20 = s[20], a21 = s[21], a22 = s[22], a23 = s[23], a24 = s[24];

    for (int r = 0; r < 24; r++) {
      // Theta
      long c0 = a00 ^ a05 ^ a10 ^ a15 ^ a20;
      long c1 = a01 ^ a06 ^ a11 ^ a16 ^ a21;
      long c2 = a02 ^ a07 ^ a12 ^ a17 ^ a22;
      long c3 = a03 ^ a08 ^ a13 ^ a18 ^ a23;
      long c4 = a04 ^ a09 ^ a14 ^ a19 ^ a24;

      long d0 = c4 ^ Long.rotateLeft(c1, 1);
      long d1 = c0 ^ Long.rotateLeft(c2, 1);
      long d2 = c1 ^ Long.rotateLeft(c3, 1);
      long d3 = c2 ^ Long.rotateLeft(c4, 1);
      long d4 = c3 ^ Long.rotateLeft(c0, 1);

      a00 ^= d0;
      a05 ^= d0;
      a10 ^= d0;
      a15 ^= d0;
      a20 ^= d0;
      a01 ^= d1;
      a06 ^= d1;
      a11 ^= d1;
      a16 ^= d1;
      a21 ^= d1;
      a02 ^= d2;
      a07 ^= d2;
      a12 ^= d2;
      a17 ^= d2;
      a22 ^= d2;
      a03 ^= d3;
      a08 ^= d3;
      a13 ^= d3;
      a18 ^= d3;
      a23 ^= d3;
      a04 ^= d4;
      a09 ^= d4;
      a14 ^= d4;
      a19 ^= d4;
      a24 ^= d4;

      // Rho and Pi
      long b00 = a00;
      long b10 = Long.rotateLeft(a01, 1);
      long b20 = Long.rotateLeft(a02, 62);
      long b05 = Long.rotateLeft(a03, 28);
      long b15 = Long.rotateLeft(a04, 27);

      long b16 = Long.rotateLeft(a05, 36);
      long b01 = Long.rotateLeft(a06, 44);
      long b11 = Long.rotateLeft(a07, 6);
      long b21 = Long.rotateLeft(a08, 55);
      long b06 = Long.rotateLeft(a09, 20);

      long b07 = Long.rotateLeft(a10, 3);
      long b17 = Long.rotateLeft(a11, 10);
      long b02 = Long.rotateLeft(a12, 43);
      long b12 = Long.rotateLeft(a13, 25);
      long b22 = Long.rotateLeft(a14, 39);

      long b23 = Long.rotateLeft(a15, 41);
      long b08 = Long.rotateLeft(a16, 45);
      long b18 = Long.rotateLeft(a17, 15);
      long b03 = Long.rotateLeft(a18, 21);
      long b13 = Long.rotateLeft(a19, 8);

      long b14 = Long.rotateLeft(a20, 18);
      long b24 = Long.rotateLeft(a21, 2);
      long b09 = Long.rotateLeft(a22, 61);
      long b19 = Long.rotateLeft(a23, 56);
      long b04 = Long.rotateLeft(a24, 14);

      // Chi + Iota
      a00 = b00 ^ (~b01 & b02) ^ RC[r];
      a01 = b01 ^ (~b02 & b03);
      a02 = b02 ^ (~b03 & b04);
      a03 = b03 ^ (~b04 & b00);
      a04 = b04 ^ (~b00 & b01);

      a05 = b05 ^ (~b06 & b07);
      a06 = b06 ^ (~b07 & b08);
      a07 = b07 ^ (~b08 & b09);
      a08 = b08 ^ (~b09 & b05);
      a09 = b09 ^ (~b05 & b06);

      a10 = b10 ^ (~b11 & b12);
      a11 = b11 ^ (~b12 & b13);
      a12 = b12 ^ (~b13 & b14);
      a13 = b13 ^ (~b14 & b10);
      a14 = b14 ^ (~b10 & b11);

      a15 = b15 ^ (~b16 & b17);
      a16 = b16 ^ (~b17 & b18);
      a17 = b17 ^ (~b18 & b19);
      a18 = b18 ^ (~b19 & b15);
      a19 = b19 ^ (~b15 & b16);

      a20 = b20 ^ (~b21 & b22);
      a21 = b21 ^ (~b22 & b23);
      a22 = b22 ^ (~b23 & b24);
      a23 = b23 ^ (~b24 & b20);
      a24 = b24 ^ (~b20 & b21);
    }

    s[0] = a00;
    s[1] = a01;
    s[2] = a02;
    s[3] = a03;
    s[4] = a04;
    s[5] = a05;
    s[6] = a06;
    s[7] = a07;
    s[8] = a08;
    s[9] = a09;
    s[10] = a10;
    s[11] = a11;
    s[12] = a12;
    s[13] = a13;
    s[14] = a14;
    s[15] = a15;
    s[16] = a16;
    s[17] = a17;
    s[18] = a18;
    s[19] = a19;
    s[20] = a20;
    s[21] = a21;
    s[22] = a22;
    s[23] = a23;
    s[24] = a24;
  }
}

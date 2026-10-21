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
package org.hyperledger.besu.crypto.altbn128;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * High-performance BN254 (alt_bn128) G1 point addition (EIP-196 ECADD) using 4x64-bit Montgomery
 * field arithmetic, {@link Math#unsignedMultiplyHigh(long, long)}, {@link VarHandle} big-endian
 * byte views, and 64-bit word-stepped Montgomery modular inversion.
 */
public final class Bn254Add {

  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  // Field modulus q = 21888242871839275222246405745257275088696311157297823662689037894645226208583
  static final long Q0 = 0x3c208c16d87cfd47L;
  static final long Q1 = 0x97816a916871ca8dL;
  static final long Q2 = 0xb85045b68181585dL;
  static final long Q3 = 0x30644e72e131a029L;

  // Montgomery constant Q_INV = -q^{-1} mod 2^64
  private static final long Q_INV = 0x87d20782e4866389L;

  // R^2 mod q where R = 2^256
  static final long R2_0 = 0xf32cfc5b538afa89L;
  static final long R2_1 = 0xb5e71911d44501fbL;
  static final long R2_2 = 0x47ab1eff0a417ff6L;
  static final long R2_3 = 0x06d89f71cab8351fL;

  // R^3 mod q where R = 2^256
  static final long R3_0 = 0x5c379276109a3f3eL;
  static final long R3_1 = 0x51e43795d825bd14L;
  static final long R3_2 = 0x0528321b6038c4aaL;
  static final long R3_3 = 0x22c85ea76c2b59cfL;

  // Curve constant B = 3 in Montgomery form (3 * R mod q)
  private static final long B_MONT_0 = 0x7a17caa950ad28d7L;
  private static final long B_MONT_1 = 0x1f6ac17ae15521b9L;
  private static final long B_MONT_2 = 0x334bea4e696bd284L;
  private static final long B_MONT_3 = 0x2a1f6744ce179d8eL;

  private Bn254Add() {}

  /**
   * Executes EIP-196 ECADD on raw byte arrays.
   *
   * @param input the input byte array (may be any length; right-padded with zeros if &lt; 128)
   * @param inputOffset offset in input array
   * @param inputLength number of available bytes in input
   * @param output the 64-byte output array to write the result (X3, Y3) in big-endian
   * @return true if points are valid and on curve, false otherwise
   */
  public static boolean ecAdd(
      final byte[] input, final int inputOffset, final int inputLength, final byte[] output) {
    if (output == null
        || output.length < 64
        || input == null
        || inputOffset < 0
        || inputLength < 0
        || inputOffset > input.length - inputLength) {
      return false;
    }

    final long x1_0, x1_1, x1_2, x1_3;
    final long y1_0, y1_1, y1_2, y1_3;
    final long x2_0, x2_1, x2_2, x2_3;
    final long y2_0, y2_1, y2_2, y2_3;

    if (inputLength >= 128) {
      x1_3 = (long) LONG_BE.get(input, inputOffset);
      x1_2 = (long) LONG_BE.get(input, inputOffset + 8);
      x1_1 = (long) LONG_BE.get(input, inputOffset + 16);
      x1_0 = (long) LONG_BE.get(input, inputOffset + 24);

      y1_3 = (long) LONG_BE.get(input, inputOffset + 32);
      y1_2 = (long) LONG_BE.get(input, inputOffset + 40);
      y1_1 = (long) LONG_BE.get(input, inputOffset + 48);
      y1_0 = (long) LONG_BE.get(input, inputOffset + 56);

      x2_3 = (long) LONG_BE.get(input, inputOffset + 64);
      x2_2 = (long) LONG_BE.get(input, inputOffset + 72);
      x2_1 = (long) LONG_BE.get(input, inputOffset + 80);
      x2_0 = (long) LONG_BE.get(input, inputOffset + 88);

      y2_3 = (long) LONG_BE.get(input, inputOffset + 96);
      y2_2 = (long) LONG_BE.get(input, inputOffset + 104);
      y2_1 = (long) LONG_BE.get(input, inputOffset + 112);
      y2_0 = (long) LONG_BE.get(input, inputOffset + 120);
    } else if (inputLength == 0) {
      LONG_BE.set(output, 0, 0L);
      LONG_BE.set(output, 8, 0L);
      LONG_BE.set(output, 16, 0L);
      LONG_BE.set(output, 24, 0L);
      LONG_BE.set(output, 32, 0L);
      LONG_BE.set(output, 40, 0L);
      LONG_BE.set(output, 48, 0L);
      LONG_BE.set(output, 56, 0L);
      return true;
    } else {
      final byte[] padded = new byte[128];
      System.arraycopy(input, inputOffset, padded, 0, inputLength);
      return ecAdd(padded, 0, 128, output);
    }

    // Validate coordinates < q
    if (!isLessThanQ(x1_0, x1_1, x1_2, x1_3)
        || !isLessThanQ(y1_0, y1_1, y1_2, y1_3)
        || !isLessThanQ(x2_0, x2_1, x2_2, x2_3)
        || !isLessThanQ(y2_0, y2_1, y2_2, y2_3)) {
      return false;
    }

    final boolean p1Inf = (x1_0 | x1_1 | x1_2 | x1_3 | y1_0 | y1_1 | y1_2 | y1_3) == 0L;
    final boolean p2Inf = (x2_0 | x2_1 | x2_2 | x2_3 | y2_0 | y2_1 | y2_2 | y2_3) == 0L;

    if (p1Inf && p2Inf) {
      LONG_BE.set(output, 0, 0L);
      LONG_BE.set(output, 8, 0L);
      LONG_BE.set(output, 16, 0L);
      LONG_BE.set(output, 24, 0L);
      LONG_BE.set(output, 32, 0L);
      LONG_BE.set(output, 40, 0L);
      LONG_BE.set(output, 48, 0L);
      LONG_BE.set(output, 56, 0L);
      return true;
    }

    // Scratchpad of 32 longs for Montgomery field operations (EA scalarized by C2)
    final long[] s = new long[32];
    // Layout in s:
    //  0..3  : X1 (Montgomery)
    //  4..7  : Y1 (Montgomery)
    //  8..11 : X2 (Montgomery)
    // 12..15 : Y2 (Montgomery)
    // 16..19 : t0
    // 20..23 : t1 (X1^2 during curve check)
    // 24..27 : t2
    // 28..31 : t3

    if (!p1Inf) {
      s[0] = x1_0;
      s[1] = x1_1;
      s[2] = x1_2;
      s[3] = x1_3;
      s[4] = y1_0;
      s[5] = y1_1;
      s[6] = y1_2;
      s[7] = y1_3;
      toMont(s, 0, s, 0);
      toMont(s, 4, s, 4);
      if (!isOnCurveMont(s, 0, 4)) {
        return false;
      }
    }

    if (p1Inf) {
      s[8] = x2_0;
      s[9] = x2_1;
      s[10] = x2_2;
      s[11] = x2_3;
      s[12] = y2_0;
      s[13] = y2_1;
      s[14] = y2_2;
      s[15] = y2_3;
      toMont(s, 8, s, 8);
      toMont(s, 12, s, 12);
      if (!isOnCurveMont(s, 8, 12)) {
        return false;
      }
      LONG_BE.set(output, 0, x2_3);
      LONG_BE.set(output, 8, x2_2);
      LONG_BE.set(output, 16, x2_1);
      LONG_BE.set(output, 24, x2_0);
      LONG_BE.set(output, 32, y2_3);
      LONG_BE.set(output, 40, y2_2);
      LONG_BE.set(output, 48, y2_1);
      LONG_BE.set(output, 56, y2_0);
      return true;
    }

    if (p2Inf) {
      LONG_BE.set(output, 0, x1_3);
      LONG_BE.set(output, 8, x1_2);
      LONG_BE.set(output, 16, x1_1);
      LONG_BE.set(output, 24, x1_0);
      LONG_BE.set(output, 32, y1_3);
      LONG_BE.set(output, 40, y1_2);
      LONG_BE.set(output, 48, y1_1);
      LONG_BE.set(output, 56, y1_0);
      return true;
    }

    // Check if X1 == X2 before converting P2 to Montgomery form!
    if (x1_0 == x2_0 && x1_1 == x2_1 && x1_2 == x2_2 && x1_3 == x2_3) {
      if (y1_0 == y2_0 && y1_1 == y2_1 && y1_2 == y2_2 && y1_3 == y2_3) {
        // Point doubling: P1 == P2 (P2 is already known to be on curve since P1 is on curve!)
        // If Y1 == 0 (impossible for y^2 = x^3 + 3 over BN254, but guard for completeness), return
        // Inf
        if ((y1_0 | y1_1 | y1_2 | y1_3) == 0L) {
          LONG_BE.set(output, 0, 0L);
          LONG_BE.set(output, 8, 0L);
          LONG_BE.set(output, 16, 0L);
          LONG_BE.set(output, 24, 0L);
          LONG_BE.set(output, 32, 0L);
          LONG_BE.set(output, 40, 0L);
          LONG_BE.set(output, 48, 0L);
          LONG_BE.set(output, 56, 0L);
          return true;
        }
        // s[20..23] ALREADY holds X1^2 from isOnCurveMont(s, 0, 4)!
        addMod(s, 20, s, 20, s, 16); // t0 = 2 * X1^2
        addMod(s, 16, s, 20, s, 16); // t0 = 3 * X1^2

        // Compute 2 * Y1 in STANDARD form (saving 1 montMul in inversion!)
        long dy0 = y1_0 << 1;
        long dy1 = (y1_1 << 1) | (y1_0 >>> 63);
        long dy2 = (y1_2 << 1) | (y1_1 >>> 63);
        long dy3 = (y1_3 << 1) | (y1_2 >>> 63);
        if (!isLessThanQ(dy0, dy1, dy2, dy3)) {
          long d0 = dy0 - Q0;
          long br = ((dy0 ^ Long.MIN_VALUE) < (Q0 ^ Long.MIN_VALUE)) ? 1L : 0L;
          long t1 = dy1 - Q1;
          long br1 = ((dy1 ^ Long.MIN_VALUE) < (Q1 ^ Long.MIN_VALUE)) ? 1L : 0L;
          dy1 = t1 - br;
          br = br1 + (((t1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
          long t2 = dy2 - Q2;
          long br2 = ((dy2 ^ Long.MIN_VALUE) < (Q2 ^ Long.MIN_VALUE)) ? 1L : 0L;
          dy2 = t2 - br;
          br = br2 + (((t2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
          dy3 = dy3 - Q3 - br;
          dy0 = d0;
        }
        s[20] = dy0;
        s[21] = dy1;
        s[22] = dy2;
        s[23] = dy3;
        montInvStdToMont(s, 20, s, 24); // t2 = (2 * Y1)^(-1) in Mont
        montMul(s, 16, s, 24, s, 16); // t0 = m = 3*X1^2 / (2*Y1) in Mont

        // X3 = m^2 - 2 * X1
        montSqr(s, 16, s, 20); // t1 = m^2
        subMod(s, 20, s, 0, s, 20); // t1 = m^2 - X1
        subMod(s, 20, s, 0, s, 20); // t1 = X3 = m^2 - 2*X1

        // Y3 = m * (X1 - X3) - Y1
        subMod(s, 0, s, 20, s, 24); // t2 = X1 - X3
        montMul(s, 16, s, 24, s, 24); // t2 = m * (X1 - X3)
        subMod(s, 24, s, 4, s, 24); // t2 = Y3
      } else {
        // X1 == X2 and Y1 != Y2: since P1 is on curve, P2 is on curve iff Y1 + Y2 == q!
        long sum0 = y1_0 + y2_0;
        long c = (Long.compareUnsigned(sum0, y1_0) < 0) ? 1L : 0L;
        long t1 = y1_1 + y2_1;
        long c1 = (Long.compareUnsigned(t1, y1_1) < 0) ? 1L : 0L;
        long sum1 = t1 + c;
        c = c1 + ((Long.compareUnsigned(sum1, t1) < 0) ? 1L : 0L);
        long t2 = y1_2 + y2_2;
        long c2 = (Long.compareUnsigned(t2, y1_2) < 0) ? 1L : 0L;
        long sum2 = t2 + c;
        c = c2 + ((Long.compareUnsigned(sum2, t2) < 0) ? 1L : 0L);
        long sum3 = y1_3 + y2_3 + c;
        if (sum0 != Q0 || sum1 != Q1 || sum2 != Q2 || sum3 != Q3) {
          return false;
        }
        // P1 == -P2 -> Point at infinity (0, 0)
        LONG_BE.set(output, 0, 0L);
        LONG_BE.set(output, 8, 0L);
        LONG_BE.set(output, 16, 0L);
        LONG_BE.set(output, 24, 0L);
        LONG_BE.set(output, 32, 0L);
        LONG_BE.set(output, 40, 0L);
        LONG_BE.set(output, 48, 0L);
        LONG_BE.set(output, 56, 0L);
        return true;
      }
    } else {
      // Point addition: P1 != P2 (and X1 != X2)
      s[8] = x2_0;
      s[9] = x2_1;
      s[10] = x2_2;
      s[11] = x2_3;
      s[12] = y2_0;
      s[13] = y2_1;
      s[14] = y2_2;
      s[15] = y2_3;

      // Compute dx = X2 - X1 in STANDARD form (saving 1 fromMont in inversion!)
      long d0 = x2_0 - x1_0;
      long br = ((x2_0 ^ Long.MIN_VALUE) < (x1_0 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long t1 = x2_1 - x1_1;
      long br1 = ((x2_1 ^ Long.MIN_VALUE) < (x1_1 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d1 = t1 - br;
      br = br1 + (((t1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      long t2 = x2_2 - x1_2;
      long br2 = ((x2_2 ^ Long.MIN_VALUE) < (x1_2 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d2 = t2 - br;
      br = br2 + (((t2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      long t3 = x2_3 - x1_3;
      long br3 = ((x2_3 ^ Long.MIN_VALUE) < (x1_3 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d3 = t3 - br;
      br = br3 + (((t3 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      if (br != 0L) {
        long s0 = d0 + Q0;
        long c = ((s0 ^ Long.MIN_VALUE) < (d0 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long u1 = d1 + Q1;
        long c1 = ((u1 ^ Long.MIN_VALUE) < (d1 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long s1 = u1 + c;
        c = c1 + (((s1 ^ Long.MIN_VALUE) < (u1 ^ Long.MIN_VALUE)) ? 1L : 0L);
        long u2 = d2 + Q2;
        long c2 = ((u2 ^ Long.MIN_VALUE) < (d2 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long s2 = u2 + c;
        c = c2 + (((s2 ^ Long.MIN_VALUE) < (u2 ^ Long.MIN_VALUE)) ? 1L : 0L);
        d3 = d3 + Q3 + c;
        d0 = s0;
        d1 = s1;
        d2 = s2;
      }

      toMont(s, 8, s, 8);
      toMont(s, 12, s, 12);
      if (!isOnCurveMont(s, 8, 12)) {
        return false;
      }

      // m = (Y2 - Y1) / (X2 - X1)
      subMod(s, 12, s, 4, s, 16); // t0 = Y2 - Y1 (in Mont)
      s[20] = d0;
      s[21] = d1;
      s[22] = d2;
      s[23] = d3;
      montInvStdToMont(s, 20, s, 24); // t2 = (X2 - X1)^(-1) in Mont
      montMul(s, 16, s, 24, s, 16); // t0 = m

      // X3 = m^2 - X1 - X2
      montSqr(s, 16, s, 20); // t1 = m^2
      subMod(s, 20, s, 0, s, 20); // t1 = m^2 - X1
      subMod(s, 20, s, 8, s, 20); // t1 = X3 = m^2 - X1 - X2

      // Y3 = m * (X1 - X3) - Y1
      subMod(s, 0, s, 20, s, 24); // t2 = X1 - X3
      montMul(s, 16, s, 24, s, 24); // t2 = m * (X1 - X3)
      subMod(s, 24, s, 4, s, 24); // t2 = Y3
    }

    // Convert X3 (at 20) and Y3 (at 24) out of Montgomery domain
    fromMont(s, 20, s, 20);
    fromMont(s, 24, s, 24);

    LONG_BE.set(output, 0, s[23]);
    LONG_BE.set(output, 8, s[22]);
    LONG_BE.set(output, 16, s[21]);
    LONG_BE.set(output, 24, s[20]);
    LONG_BE.set(output, 32, s[27]);
    LONG_BE.set(output, 40, s[26]);
    LONG_BE.set(output, 48, s[25]);
    LONG_BE.set(output, 56, s[24]);
    return true;
  }

  static boolean isLessThanQ(final long a0, final long a1, final long a2, final long a3) {
    if (a3 != Q3) return Long.compareUnsigned(a3, Q3) < 0;
    if (a2 != Q2) return Long.compareUnsigned(a2, Q2) < 0;
    if (a1 != Q1) return Long.compareUnsigned(a1, Q1) < 0;
    return Long.compareUnsigned(a0, Q0) < 0;
  }

  /**
   * Checks if (X, Y) in Montgomery form satisfies Y^2 == X^3 + B_MONT (mod q). Leaves X^2 in
   * s[20..23] and X^3 + B_MONT in s[28..31].
   */
  private static boolean isOnCurveMont(final long[] s, final int xOff, final int yOff) {
    // t0 (16) = Y^2
    montSqr(s, yOff, s, 16);
    // t1 (20) = X^2
    montSqr(s, xOff, s, 20);
    // t3 (28) = X^3
    montMul(s, 20, s, xOff, s, 28);
    // t3 (28) = X^3 + B_MONT
    s[24] = B_MONT_0;
    s[25] = B_MONT_1;
    s[26] = B_MONT_2;
    s[27] = B_MONT_3;
    addMod(s, 28, s, 24, s, 28);

    return s[16] == s[28] && s[17] == s[29] && s[18] == s[30] && s[19] == s[31];
  }

  static void toMont(final long[] a, final int aOff, final long[] out, final int outOff) {
    montMulConst(
        a[aOff], a[aOff + 1], a[aOff + 2], a[aOff + 3], R2_0, R2_1, R2_2, R2_3, out, outOff);
  }

  static void fromMont(final long[] a, final int aOff, final long[] out, final int outOff) {
    long t0 = a[aOff];
    long t1 = a[aOff + 1];
    long t2 = a[aOff + 2];
    long t3 = a[aOff + 3];

    // Step 0
    long m = t0 * Q_INV;
    long c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    long lo = m * Q1;
    long hi = Math.unsignedMultiplyHigh(m, Q1);
    long sum = t1 + lo;
    long c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    t3 = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    // Step 1
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    t3 = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    // Step 2
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    t3 = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    // Step 3
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    t3 = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    if (!isLessThanQ(t0, t1, t2, t3)) {
      long b0 = t0 - Q0;
      long borrow = (Long.compareUnsigned(t0, Q0) < 0) ? 1L : 0L;
      long b1 = t1 - Q1;
      long br1 = (Long.compareUnsigned(t1, Q1) < 0) ? 1L : 0L;
      long s1 = b1 - borrow;
      borrow = br1 + ((Long.compareUnsigned(b1, borrow) < 0) ? 1L : 0L);
      long b2 = t2 - Q2;
      long br2 = (Long.compareUnsigned(t2, Q2) < 0) ? 1L : 0L;
      long s2 = b2 - borrow;
      borrow = br2 + ((Long.compareUnsigned(b2, borrow) < 0) ? 1L : 0L);
      long s3 = t3 - Q3 - borrow;
      t0 = b0;
      t1 = s1;
      t2 = s2;
      t3 = s3;
    }

    out[outOff] = t0;
    out[outOff + 1] = t1;
    out[outOff + 2] = t2;
    out[outOff + 3] = t3;
  }

  static void addMod(
      final long[] a,
      final int aOff,
      final long[] b,
      final int bOff,
      final long[] out,
      final int outOff) {
    final long a0 = a[aOff], a1 = a[aOff + 1], a2 = a[aOff + 2], a3 = a[aOff + 3];
    final long b0 = b[bOff], b1 = b[bOff + 1], b2 = b[bOff + 2], b3 = b[bOff + 3];

    long s0 = a0 + b0;
    long c = (Long.compareUnsigned(s0, a0) < 0) ? 1L : 0L;

    long t1 = a1 + b1;
    long c1 = (Long.compareUnsigned(t1, a1) < 0) ? 1L : 0L;
    long s1 = t1 + c;
    c = c1 + ((Long.compareUnsigned(s1, t1) < 0) ? 1L : 0L);

    long t2 = a2 + b2;
    long c2 = (Long.compareUnsigned(t2, a2) < 0) ? 1L : 0L;
    long s2 = t2 + c;
    c = c2 + ((Long.compareUnsigned(s2, t2) < 0) ? 1L : 0L);

    long s3 = a3 + b3 + c;

    if (!isLessThanQ(s0, s1, s2, s3)) {
      long d0 = s0 - Q0;
      long br = (Long.compareUnsigned(s0, Q0) < 0) ? 1L : 0L;

      long d1 = s1 - Q1;
      long br1 = (Long.compareUnsigned(s1, Q1) < 0) ? 1L : 0L;
      long r1 = d1 - br;
      br = br1 + ((Long.compareUnsigned(d1, br) < 0) ? 1L : 0L);

      long d2 = s2 - Q2;
      long br2 = (Long.compareUnsigned(s2, Q2) < 0) ? 1L : 0L;
      long r2 = d2 - br;
      br = br2 + ((Long.compareUnsigned(d2, br) < 0) ? 1L : 0L);

      s3 = s3 - Q3 - br;
      s0 = d0;
      s1 = r1;
      s2 = r2;
    }

    out[outOff] = s0;
    out[outOff + 1] = s1;
    out[outOff + 2] = s2;
    out[outOff + 3] = s3;
  }

  static void subMod(
      final long[] a,
      final int aOff,
      final long[] b,
      final int bOff,
      final long[] out,
      final int outOff) {
    final long a0 = a[aOff], a1 = a[aOff + 1], a2 = a[aOff + 2], a3 = a[aOff + 3];
    final long b0 = b[bOff], b1 = b[bOff + 1], b2 = b[bOff + 2], b3 = b[bOff + 3];

    long d0 = a0 - b0;
    long br = (Long.compareUnsigned(a0, b0) < 0) ? 1L : 0L;

    long t1 = a1 - b1;
    long br1 = (Long.compareUnsigned(a1, b1) < 0) ? 1L : 0L;
    long d1 = t1 - br;
    br = br1 + ((Long.compareUnsigned(t1, br) < 0) ? 1L : 0L);

    long t2 = a2 - b2;
    long br2 = (Long.compareUnsigned(a2, b2) < 0) ? 1L : 0L;
    long d2 = t2 - br;
    br = br2 + ((Long.compareUnsigned(t2, br) < 0) ? 1L : 0L);

    long t3 = a3 - b3;
    long br3 = (Long.compareUnsigned(a3, b3) < 0) ? 1L : 0L;
    long d3 = t3 - br;
    br = br3 + ((Long.compareUnsigned(t3, br) < 0) ? 1L : 0L);

    if (br != 0L) {
      long s0 = d0 + Q0;
      long c = (Long.compareUnsigned(s0, d0) < 0) ? 1L : 0L;

      long u1 = d1 + Q1;
      long c1 = (Long.compareUnsigned(u1, d1) < 0) ? 1L : 0L;
      long s1 = u1 + c;
      c = c1 + ((Long.compareUnsigned(s1, u1) < 0) ? 1L : 0L);

      long u2 = d2 + Q2;
      long c2 = (Long.compareUnsigned(u2, d2) < 0) ? 1L : 0L;
      long s2 = u2 + c;
      c = c2 + ((Long.compareUnsigned(s2, u2) < 0) ? 1L : 0L);

      d3 = d3 + Q3 + c;
      d0 = s0;
      d1 = s1;
      d2 = s2;
    }

    out[outOff] = d0;
    out[outOff + 1] = d1;
    out[outOff + 2] = d2;
    out[outOff + 3] = d3;
  }

  static void montSqr(final long[] a, final int aOff, final long[] out, final int outOff) {
    montMul(a, aOff, a, aOff, out, outOff);
  }

  static void montMul(
      final long[] a,
      final int aOff,
      final long[] b,
      final int bOff,
      final long[] out,
      final int outOff) {
    montMulConst(
        a[aOff],
        a[aOff + 1],
        a[aOff + 2],
        a[aOff + 3],
        b[bOff],
        b[bOff + 1],
        b[bOff + 2],
        b[bOff + 3],
        out,
        outOff);
  }

  /**
   * Coarsely Integrated Operand Scanning (CIOS) Montgomery multiplication. Since Q3 =
   * 0x30644e72e131a029 &lt; 2^62 - 1, the intermediate accumulator never exceeds 4 limbs.
   */
  private static void montMulConst(
      final long a0,
      final long a1,
      final long a2,
      final long a3,
      final long b0,
      final long b1,
      final long b2,
      final long b3,
      final long[] out,
      final int outOff) {
    long t0, t1, t2, t3;
    long lo, hi, sum, c, c1, m;

    // --- Iteration i = 0 (multiply by b0) ---
    lo = a0 * b0;
    c = Math.unsignedMultiplyHigh(a0, b0);
    t0 = lo;

    lo = a1 * b0;
    hi = Math.unsignedMultiplyHigh(a1, b0);
    t1 = lo + c;
    c = hi + ((Long.compareUnsigned(t1, lo) < 0) ? 1L : 0L);

    lo = a2 * b0;
    hi = Math.unsignedMultiplyHigh(a2, b0);
    t2 = lo + c;
    c = hi + ((Long.compareUnsigned(t2, lo) < 0) ? 1L : 0L);

    lo = a3 * b0;
    hi = Math.unsignedMultiplyHigh(a3, b0);
    t3 = lo + c;
    long t4 = hi + ((Long.compareUnsigned(t3, lo) < 0) ? 1L : 0L);

    // Reduction step 0
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);
    t3 = t4 + c;

    // --- Iteration i = 1 (multiply by b1) ---
    lo = a0 * b1;
    hi = Math.unsignedMultiplyHigh(a0, b1);
    t0 += lo;
    c = hi + ((Long.compareUnsigned(t0, lo) < 0) ? 1L : 0L);

    lo = a1 * b1;
    hi = Math.unsignedMultiplyHigh(a1, b1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = a2 * b1;
    hi = Math.unsignedMultiplyHigh(a2, b1);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    lo = a3 * b1;
    hi = Math.unsignedMultiplyHigh(a3, b1);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t3 = sum + c;
    t4 = hi + c1 + ((Long.compareUnsigned(t3, sum) < 0) ? 1L : 0L);

    // Reduction step 1
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);
    t3 = t4 + c;

    // --- Iteration i = 2 (multiply by b2) ---
    lo = a0 * b2;
    hi = Math.unsignedMultiplyHigh(a0, b2);
    t0 += lo;
    c = hi + ((Long.compareUnsigned(t0, lo) < 0) ? 1L : 0L);

    lo = a1 * b2;
    hi = Math.unsignedMultiplyHigh(a1, b2);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = a2 * b2;
    hi = Math.unsignedMultiplyHigh(a2, b2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    lo = a3 * b2;
    hi = Math.unsignedMultiplyHigh(a3, b2);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t3 = sum + c;
    t4 = hi + c1 + ((Long.compareUnsigned(t3, sum) < 0) ? 1L : 0L);

    // Reduction step 2
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);
    t3 = t4 + c;

    // --- Iteration i = 3 (multiply by b3) ---
    lo = a0 * b3;
    hi = Math.unsignedMultiplyHigh(a0, b3);
    t0 += lo;
    c = hi + ((Long.compareUnsigned(t0, lo) < 0) ? 1L : 0L);

    lo = a1 * b3;
    hi = Math.unsignedMultiplyHigh(a1, b3);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = a2 * b3;
    hi = Math.unsignedMultiplyHigh(a2, b3);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);

    lo = a3 * b3;
    hi = Math.unsignedMultiplyHigh(a3, b3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t3 = sum + c;
    t4 = hi + c1 + ((Long.compareUnsigned(t3, sum) < 0) ? 1L : 0L);

    // Reduction step 3
    m = t0 * Q_INV;
    c = Math.unsignedMultiplyHigh(m, Q0) + (t0 != 0L ? 1L : 0L);

    lo = m * Q1;
    hi = Math.unsignedMultiplyHigh(m, Q1);
    sum = t1 + lo;
    c1 = (Long.compareUnsigned(sum, t1) < 0) ? 1L : 0L;
    t0 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t0, sum) < 0) ? 1L : 0L);

    lo = m * Q2;
    hi = Math.unsignedMultiplyHigh(m, Q2);
    sum = t2 + lo;
    c1 = (Long.compareUnsigned(sum, t2) < 0) ? 1L : 0L;
    t1 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t1, sum) < 0) ? 1L : 0L);

    lo = m * Q3;
    hi = Math.unsignedMultiplyHigh(m, Q3);
    sum = t3 + lo;
    c1 = (Long.compareUnsigned(sum, t3) < 0) ? 1L : 0L;
    t2 = sum + c;
    c = hi + c1 + ((Long.compareUnsigned(t2, sum) < 0) ? 1L : 0L);
    t3 = t4 + c;

    // Final conditional subtraction of Q
    if (!isLessThanQ(t0, t1, t2, t3)) {
      long d0 = t0 - Q0;
      long br = (Long.compareUnsigned(t0, Q0) < 0) ? 1L : 0L;

      long d1 = t1 - Q1;
      long br1 = (Long.compareUnsigned(t1, Q1) < 0) ? 1L : 0L;
      long r1 = d1 - br;
      br = br1 + ((Long.compareUnsigned(d1, br) < 0) ? 1L : 0L);

      long d2 = t2 - Q2;
      long br2 = (Long.compareUnsigned(t2, Q2) < 0) ? 1L : 0L;
      long r2 = d2 - br;
      br = br2 + ((Long.compareUnsigned(d2, br) < 0) ? 1L : 0L);

      t3 = t3 - Q3 - br;
      t0 = d0;
      t1 = r1;
      t2 = r2;
    }

    out[outOff] = t0;
    out[outOff + 1] = t1;
    out[outOff + 2] = t2;
    out[outOff + 3] = t3;
  }

  /**
   * Computes a * 2^(-k) mod q for 1 &lt;= k &lt;= 64 in a SINGLE 64-bit Montgomery reduction step!
   */
  private static long montHalveStep0(final long b0, final int k) {
    final long mask = (k == 64) ? -1L : (1L << k) - 1L;
    return (b0 * Q_INV) & mask;
  }

  /**
   * Takes {@code a} in STANDARD form ({@code x mod q}) and computes {@code x^(-1) * R mod q} (its
   * inverse in Montgomery form) using ZERO full 4x4 Montgomery multiplications!
   *
   * <p>After Phase 1 of Kaliski's algorithm on {@code x} (standard form), {@code r = -x^(-1) * 2^k
   * mod q} where {@code 1 <= k <= 512}. Negating {@code r} gives {@code x^(-1) * 2^k mod q}. If
   * {@code k >= 256}, we perform {@code k - 256} word-level Montgomery halvings to reach {@code
   * x^(-1) * 2^256 mod q} directly! (If {@code k < 256}, we montMul by {@code R^2} and halve {@code
   * k} times.)
   */
  static void montInvStdToMont(final long[] a, final int aOff, final long[] out, final int outOff) {
    long u0 = Q0, u1 = Q1, u2 = Q2, u3 = Q3;
    long v0 = a[aOff], v1 = a[aOff + 1], v2 = a[aOff + 2], v3 = a[aOff + 3];
    if ((v0 | v1 | v2 | v3) == 0L) {
      out[outOff] = 0L;
      out[outOff + 1] = 0L;
      out[outOff + 2] = 0L;
      out[outOff + 3] = 0L;
      return;
    }
    long r0 = 0L, r1 = 0L, r2 = 0L, r3 = 0L;
    long s0 = 1L, s1 = 0L, s2 = 0L, s3 = 0L;
    int k = 0;

    while ((v0 & 1L) == 0L) {
      final int tz = Long.numberOfTrailingZeros(v0);
      if (tz == 64) {
        v0 = v1;
        v1 = v2;
        v2 = v3;
        v3 = 0L;
        r3 = r2;
        r2 = r1;
        r1 = r0;
        r0 = 0L;
      } else {
        final int invTz = 64 - tz;
        v0 = (v0 >>> tz) | (v1 << invTz);
        v1 = (v1 >>> tz) | (v2 << invTz);
        v2 = (v2 >>> tz) | (v3 << invTz);
        v3 >>>= tz;
        r3 = (r3 << tz) | (r2 >>> invTz);
        r2 = (r2 << tz) | (r1 >>> invTz);
        r1 = (r1 << tz) | (r0 >>> invTz);
        r0 <<= tz;
      }
      k += tz;
    }

    while (true) {
      final boolean uGtV;
      if (u3 != v3) uGtV = (u3 ^ Long.MIN_VALUE) > (v3 ^ Long.MIN_VALUE);
      else if (u2 != v2) uGtV = (u2 ^ Long.MIN_VALUE) > (v2 ^ Long.MIN_VALUE);
      else if (u1 != v1) uGtV = (u1 ^ Long.MIN_VALUE) > (v1 ^ Long.MIN_VALUE);
      else uGtV = (u0 ^ Long.MIN_VALUE) > (v0 ^ Long.MIN_VALUE);

      if (uGtV) {
        long d0 = u0 - v0;
        long br = ((u0 ^ Long.MIN_VALUE) < (v0 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long t1 = u1 - v1;
        long br1 = ((u1 ^ Long.MIN_VALUE) < (v1 ^ Long.MIN_VALUE)) ? 1L : 0L;
        u1 = t1 - br;
        br = br1 + (((t1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
        long t2 = u2 - v2;
        long br2 = ((u2 ^ Long.MIN_VALUE) < (v2 ^ Long.MIN_VALUE)) ? 1L : 0L;
        u2 = t2 - br;
        br = br2 + (((t2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
        u3 = u3 - v3 - br;
        u0 = d0;

        long a0 = r0 + s0;
        long cr = ((a0 ^ Long.MIN_VALUE) < (r0 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long x1 = r1 + s1;
        long cr1 = ((x1 ^ Long.MIN_VALUE) < (r1 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long a1 = x1 + cr;
        cr = cr1 + (((a1 ^ Long.MIN_VALUE) < (x1 ^ Long.MIN_VALUE)) ? 1L : 0L);
        long x2 = r2 + s2;
        long cr2 = ((x2 ^ Long.MIN_VALUE) < (r2 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long a2 = x2 + cr;
        cr = cr2 + (((a2 ^ Long.MIN_VALUE) < (x2 ^ Long.MIN_VALUE)) ? 1L : 0L);
        r3 = r3 + s3 + cr;
        r0 = a0;
        r1 = a1;
        r2 = a2;

        do {
          final int tz = Long.numberOfTrailingZeros(u0);
          if (tz == 64) {
            u0 = u1;
            u1 = u2;
            u2 = u3;
            u3 = 0L;
            s3 = s2;
            s2 = s1;
            s1 = s0;
            s0 = 0L;
          } else {
            final int invTz = 64 - tz;
            u0 = (u0 >>> tz) | (u1 << invTz);
            u1 = (u1 >>> tz) | (u2 << invTz);
            u2 = (u2 >>> tz) | (u3 << invTz);
            u3 >>>= tz;
            s3 = (s3 << tz) | (s2 >>> invTz);
            s2 = (s2 << tz) | (s1 >>> invTz);
            s1 = (s1 << tz) | (s0 >>> invTz);
            s0 <<= tz;
          }
          k += tz;
        } while ((u0 & 1L) == 0L);
      } else {
        long d0 = v0 - u0;
        long br = ((v0 ^ Long.MIN_VALUE) < (u0 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long t1 = v1 - u1;
        long br1 = ((v1 ^ Long.MIN_VALUE) < (u1 ^ Long.MIN_VALUE)) ? 1L : 0L;
        v1 = t1 - br;
        br = br1 + (((t1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
        long t2 = v2 - u2;
        long br2 = ((v2 ^ Long.MIN_VALUE) < (u2 ^ Long.MIN_VALUE)) ? 1L : 0L;
        v2 = t2 - br;
        br = br2 + (((t2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
        v3 = v3 - u3 - br;
        v0 = d0;

        long a0 = r0 + s0;
        long cr = ((a0 ^ Long.MIN_VALUE) < (r0 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long x1 = r1 + s1;
        long cr1 = ((x1 ^ Long.MIN_VALUE) < (r1 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long a1 = x1 + cr;
        cr = cr1 + (((a1 ^ Long.MIN_VALUE) < (x1 ^ Long.MIN_VALUE)) ? 1L : 0L);
        long x2 = r2 + s2;
        long cr2 = ((x2 ^ Long.MIN_VALUE) < (r2 ^ Long.MIN_VALUE)) ? 1L : 0L;
        long a2 = x2 + cr;
        cr = cr2 + (((a2 ^ Long.MIN_VALUE) < (x2 ^ Long.MIN_VALUE)) ? 1L : 0L);
        s3 = r3 + s3 + cr;
        s0 = a0;
        s1 = a1;
        s2 = a2;

        if ((v0 | v1 | v2 | v3) == 0L) {
          break;
        }
        do {
          final int tz = Long.numberOfTrailingZeros(v0);
          if (tz == 64) {
            v0 = v1;
            v1 = v2;
            v2 = v3;
            v3 = 0L;
            r3 = r2;
            r2 = r1;
            r1 = r0;
            r0 = 0L;
          } else {
            final int invTz = 64 - tz;
            v0 = (v0 >>> tz) | (v1 << invTz);
            v1 = (v1 >>> tz) | (v2 << invTz);
            v2 = (v2 >>> tz) | (v3 << invTz);
            v3 >>>= tz;
            r3 = (r3 << tz) | (r2 >>> invTz);
            r2 = (r2 << tz) | (r1 >>> invTz);
            r1 = (r1 << tz) | (r0 >>> invTz);
            r0 <<= tz;
          }
          k += tz;
        } while ((v0 & 1L) == 0L);
      }
    }

    if (!isLessThanQ(r0, r1, r2, r3)) {
      long d0 = r0 - Q0;
      long br = ((r0 ^ Long.MIN_VALUE) < (Q0 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d1 = r1 - Q1;
      long br1 = ((r1 ^ Long.MIN_VALUE) < (Q1 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x1 = d1 - br;
      br = br1 + (((d1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      long d2 = r2 - Q2;
      long br2 = ((r2 ^ Long.MIN_VALUE) < (Q2 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x2 = d2 - br;
      br = br2 + (((d2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      r3 = r3 - Q3 - br;
      r0 = d0;
      r1 = x1;
      r2 = x2;
    }

    if ((r0 | r1 | r2 | r3) != 0L) {
      long d0 = Q0 - r0;
      long br = ((Q0 ^ Long.MIN_VALUE) < (r0 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d1 = Q1 - r1;
      long br1 = ((Q1 ^ Long.MIN_VALUE) < (r1 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x1 = d1 - br;
      br = br1 + (((d1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      long d2 = Q2 - r2;
      long br2 = ((Q2 ^ Long.MIN_VALUE) < (r2 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x2 = d2 - br;
      br = br2 + (((d2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      r3 = Q3 - r3 - br;
      r0 = d0;
      r1 = x1;
      r2 = x2;
    }

    int rem;
    if (k >= 256) {
      rem = k - 256;
    } else {
      montMulConst(r0, r1, r2, r3, R2_0, R2_1, R2_2, R2_3, out, outOff);
      r0 = out[outOff];
      r1 = out[outOff + 1];
      r2 = out[outOff + 2];
      r3 = out[outOff + 3];
      rem = k;
    }

    while (rem > 0) {
      final int step = Math.min(rem, 64);
      final long m = montHalveStep0(r0, step);
      long lo = m * Q0;
      long hi = Math.unsignedMultiplyHigh(m, Q0);
      long t0 = r0 + lo;
      long cr = hi + (((t0 ^ Long.MIN_VALUE) < (r0 ^ Long.MIN_VALUE)) ? 1L : 0L);

      lo = m * Q1;
      hi = Math.unsignedMultiplyHigh(m, Q1);
      long sum = r1 + lo;
      long cr1 = ((sum ^ Long.MIN_VALUE) < (r1 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long t1 = sum + cr;
      cr = hi + cr1 + (((t1 ^ Long.MIN_VALUE) < (sum ^ Long.MIN_VALUE)) ? 1L : 0L);

      lo = m * Q2;
      hi = Math.unsignedMultiplyHigh(m, Q2);
      sum = r2 + lo;
      cr1 = ((sum ^ Long.MIN_VALUE) < (r2 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long t2 = sum + cr;
      cr = hi + cr1 + (((t2 ^ Long.MIN_VALUE) < (sum ^ Long.MIN_VALUE)) ? 1L : 0L);

      lo = m * Q3;
      hi = Math.unsignedMultiplyHigh(m, Q3);
      sum = r3 + lo;
      cr1 = ((sum ^ Long.MIN_VALUE) < (r3 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long t3 = sum + cr;
      long t4 = hi + cr1 + (((t3 ^ Long.MIN_VALUE) < (sum ^ Long.MIN_VALUE)) ? 1L : 0L);

      if (step == 64) {
        r0 = t1;
        r1 = t2;
        r2 = t3;
        r3 = t4;
      } else {
        final int invStep = 64 - step;
        r0 = (t0 >>> step) | (t1 << invStep);
        r1 = (t1 >>> step) | (t2 << invStep);
        r2 = (t2 >>> step) | (t3 << invStep);
        r3 = (t3 >>> step) | (t4 << invStep);
      }
      rem -= step;
    }

    if (!isLessThanQ(r0, r1, r2, r3)) {
      long d0 = r0 - Q0;
      long br = ((r0 ^ Long.MIN_VALUE) < (Q0 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long d1 = r1 - Q1;
      long br1 = ((r1 ^ Long.MIN_VALUE) < (Q1 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x1 = d1 - br;
      br = br1 + (((d1 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      long d2 = r2 - Q2;
      long br2 = ((r2 ^ Long.MIN_VALUE) < (Q2 ^ Long.MIN_VALUE)) ? 1L : 0L;
      long x2 = d2 - br;
      br = br2 + (((d2 ^ Long.MIN_VALUE) < (br ^ Long.MIN_VALUE)) ? 1L : 0L);
      r3 = r3 - Q3 - br;
      r0 = d0;
      r1 = x1;
      r2 = x2;
    }

    out[outOff] = r0;
    out[outOff + 1] = r1;
    out[outOff + 2] = r2;
    out[outOff + 3] = r3;
  }

  /**
   * Computes the Montgomery inverse of {@code a} (which is in Montgomery form {@code x * R mod q}):
   * returns {@code x^(-1) * R mod q}.
   */
  static void montInv(final long[] a, final int aOff, final long[] out, final int outOff) {
    fromMont(a, aOff, out, outOff);
    montInvStdToMont(out, outOff, out, outOff);
  }
}

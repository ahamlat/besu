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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.jupiter.api.Test;

class Bn254AddTest {

  private static final BigInteger Q = FieldElement.FIELD_MODULUS;

  private static Bytes legacyAdd(final Bytes input) {
    final BigInteger x1 = extractParameter(input, 0, 32);
    final BigInteger y1 = extractParameter(input, 32, 32);
    final BigInteger x2 = extractParameter(input, 64, 32);
    final BigInteger y2 = extractParameter(input, 96, 32);

    if (x1.compareTo(Q) >= 0
        || y1.compareTo(Q) >= 0
        || x2.compareTo(Q) >= 0
        || y2.compareTo(Q) >= 0) {
      return null;
    }

    final AltBn128Point p1 = new AltBn128Point(Fq.create(x1), Fq.create(y1));
    final AltBn128Point p2 = new AltBn128Point(Fq.create(x2), Fq.create(y2));
    if (!p1.isOnCurve() || !p2.isOnCurve()) {
      return null;
    }
    final AltBn128Point sum = p1.add(p2);
    final Bytes x = sum.getX().toBytes();
    final Bytes y = sum.getY().toBytes();
    final MutableBytes result = MutableBytes.create(64);
    x.copyTo(result, 32 - x.size());
    y.copyTo(result, 64 - y.size());
    return result.copy();
  }

  private static BigInteger extractParameter(
      final Bytes input, final int offset, final int length) {
    if (offset > input.size() || length == 0) {
      return BigInteger.ZERO;
    }
    final byte[] raw = Arrays.copyOfRange(input.toArray(), offset, offset + length);
    return new BigInteger(1, raw);
  }

  private static Bytes fastAdd(final Bytes input) {
    final byte[] out = new byte[64];
    final byte[] raw = input.toArrayUnsafe();
    if (!Bn254Add.ecAdd(raw, 0, raw.length, out)) {
      return null;
    }
    return Bytes.wrap(out);
  }

  private static Bytes encodePoint(final AltBn128Point p) {
    final Bytes x = p.getX().toBytes();
    final Bytes y = p.getY().toBytes();
    final MutableBytes result = MutableBytes.create(64);
    x.copyTo(result, 32 - x.size());
    y.copyTo(result, 64 - y.size());
    return result;
  }

  private static Bytes encodeCoords(
      final BigInteger x1, final BigInteger y1, final BigInteger x2, final BigInteger y2) {
    final MutableBytes out = MutableBytes.create(128);
    writeBigInt32(x1, out, 0);
    writeBigInt32(y1, out, 32);
    writeBigInt32(x2, out, 64);
    writeBigInt32(y2, out, 96);
    return out;
  }

  private static void writeBigInt32(
      final BigInteger v, final MutableBytes dest, final int destOff) {
    final byte[] raw = v.toByteArray();
    final int srcPos = Math.max(0, raw.length - 32);
    final int len = Math.min(32, raw.length);
    dest.set(destOff + (32 - len), Bytes.wrap(raw, srcPos, len));
  }

  private static BigInteger toBigInt(final long[] s, final int off) {
    final byte[] b = new byte[32];
    for (int i = 0; i < 4; i++) {
      long v = s[off + (3 - i)];
      for (int j = 7; j >= 0; j--) {
        b[i * 8 + j] = (byte) (v & 0xFF);
        v >>>= 8;
      }
    }
    return new BigInteger(1, b);
  }

  private static void fromBigInt(final BigInteger bi, final long[] s, final int off) {
    final byte[] raw = bi.toByteArray();
    final byte[] b = new byte[32];
    final int srcPos = Math.max(0, raw.length - 32);
    final int len = Math.min(32, raw.length);
    System.arraycopy(raw, srcPos, b, 32 - len, len);
    for (int i = 0; i < 4; i++) {
      long v = 0;
      for (int j = 0; j < 8; j++) {
        v = (v << 8) | (b[i * 8 + j] & 0xFFL);
      }
      s[off + (3 - i)] = v;
    }
  }

  @Test
  void shouldVerifyFieldOperations() {
    final BigInteger a =
        new BigInteger("1581d9d4d7eb0e3cdc75739f7098479097d579573f23e70b07cbd40a5d97bbc1", 16);
    final BigInteger b =
        new BigInteger("18da69b1d2cb7b89fdb3bba2524bf135453ce828faea190d8f4d48d572cbe3fe", 16);

    final long[] s = new long[32];
    fromBigInt(a, s, 0);
    fromBigInt(b, s, 4);

    // Test toMont and fromMont roundtrip
    Bn254Add.toMont(s, 0, s, 8);
    Bn254Add.fromMont(s, 8, s, 12);
    assertThat(toBigInt(s, 12)).as("toMont/fromMont roundtrip").isEqualTo(a);

    // Test montMul
    Bn254Add.toMont(s, 4, s, 12);
    Bn254Add.montMul(s, 8, s, 12, s, 16);
    Bn254Add.fromMont(s, 16, s, 16);
    assertThat(toBigInt(s, 16)).as("montMul").isEqualTo(a.multiply(b).mod(Q));

    // Test montSqr
    Bn254Add.montSqr(s, 8, s, 16);
    Bn254Add.fromMont(s, 16, s, 16);
    assertThat(toBigInt(s, 16)).as("montSqr").isEqualTo(a.multiply(a).mod(Q));

    // Test addMod and subMod
    Bn254Add.addMod(s, 0, s, 4, s, 16);
    assertThat(toBigInt(s, 16)).as("addMod").isEqualTo(a.add(b).mod(Q));
    Bn254Add.subMod(s, 0, s, 4, s, 16);
    assertThat(toBigInt(s, 16)).as("subMod (a < b)").isEqualTo(a.subtract(b).mod(Q));
    Bn254Add.subMod(s, 4, s, 0, s, 16);
    assertThat(toBigInt(s, 16)).as("subMod (b > a)").isEqualTo(b.subtract(a).mod(Q));

    // Test montInv and montInvStdToMont
    Bn254Add.montInv(s, 8, s, 20);
    Bn254Add.fromMont(s, 20, s, 24);
    assertThat(toBigInt(s, 24)).as("montInv").isEqualTo(a.modInverse(Q));

    Bn254Add.montInvStdToMont(s, 0, s, 20);
    Bn254Add.fromMont(s, 20, s, 24);
    assertThat(toBigInt(s, 24)).as("montInvStdToMont").isEqualTo(a.modInverse(Q));
  }

  @Test
  void shouldVerifyFieldInversionBoundaryAndRandomValues() {
    final long[] s = new long[16];

    // Zero inversion guard
    Arrays.fill(s, 0L);
    s[4] = 123L;
    Bn254Add.montInvStdToMont(s, 0, s, 4);
    assertThat(toBigInt(s, 4)).as("montInvStdToMont(0)").isEqualTo(BigInteger.ZERO);

    final List<BigInteger> boundaryInputs =
        List.of(
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.valueOf(3),
            BigInteger.valueOf(4),
            BigInteger.valueOf(65537),
            BigInteger.ONE.shiftLeft(63),
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(64),
            BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(128),
            BigInteger.ONE.shiftLeft(128).add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(192).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(192),
            BigInteger.ONE.shiftLeft(192).add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(253),
            Q.subtract(BigInteger.ONE),
            Q.subtract(BigInteger.TWO),
            Q.subtract(BigInteger.valueOf(3)),
            Q.subtract(BigInteger.ONE.shiftLeft(64)),
            Q.subtract(BigInteger.ONE.shiftLeft(128)),
            Q.subtract(BigInteger.ONE.shiftLeft(192)),
            Q.shiftRight(1),
            Q.shiftRight(2));

    for (final BigInteger val : boundaryInputs) {
      fromBigInt(val, s, 0);
      // Test out-of-place
      Bn254Add.montInvStdToMont(s, 0, s, 4);
      Bn254Add.fromMont(s, 4, s, 8);
      final BigInteger expected = val.modInverse(Q);
      assertThat(toBigInt(s, 8)).as("inv(%s)", val.toString(16)).isEqualTo(expected);

      // Test in-place aliasing (a == out, aOff == outOff)
      fromBigInt(val, s, 12);
      Bn254Add.montInvStdToMont(s, 12, s, 12);
      Bn254Add.fromMont(s, 12, s, 12);
      assertThat(toBigInt(s, 12)).as("in-place inv(%s)", val.toString(16)).isEqualTo(expected);
    }

    // 500 random field elements
    final Random rng = new Random(0xB254_ADD_7E57L);
    for (int i = 0; i < 500; i++) {
      BigInteger r;
      do {
        r = new BigInteger(254, rng);
      } while (r.signum() == 0 || r.compareTo(Q) >= 0);

      fromBigInt(r, s, 0);
      Bn254Add.montInvStdToMont(s, 0, s, 4);
      Bn254Add.fromMont(s, 4, s, 8);
      assertThat(toBigInt(s, 8)).isEqualTo(r.modInverse(Q));
    }
  }

  @Test
  void shouldHandleNullAndOutOfBoundsArrayArgumentsGracefully() {
    final byte[] validIn = new byte[128];
    final byte[] validOut = new byte[64];

    assertThat(Bn254Add.ecAdd(null, 0, 0, validOut)).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, 0, 128, null)).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, 0, 128, new byte[63])).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, -1, 128, validOut)).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, 0, -1, validOut)).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, 1, 128, validOut)).isFalse();
    assertThat(Bn254Add.ecAdd(validIn, 100, Integer.MAX_VALUE, validOut)).isFalse();
  }

  @Test
  void shouldHandleNonZeroInputOffsetAndOverlongBuffers() {
    final AltBn128Point g = AltBn128Point.g1();
    final AltBn128Point g2 = g.add(g);
    final Bytes payload = Bytes.concatenate(encodePoint(g), encodePoint(g2));
    final Bytes expected = legacyAdd(payload);

    // Embed payload at offset 37 inside a larger buffer with non-zero bytes before and after
    final byte[] backing = new byte[256];
    Arrays.fill(backing, (byte) 0xFF);
    System.arraycopy(payload.toArrayUnsafe(), 0, backing, 37, 128);

    final byte[] out = new byte[64];
    assertThat(Bn254Add.ecAdd(backing, 37, 128, out)).isTrue();
    assertThat(Bytes.wrap(out)).isEqualTo(expected);

    // Overlong inputLength (192 bytes) starting at offset 37: trailing 0xFF bytes after 128 must be
    // ignored
    Arrays.fill(out, (byte) 0);
    assertThat(Bn254Add.ecAdd(backing, 37, 192, out)).isTrue();
    assertThat(Bytes.wrap(out)).isEqualTo(expected);

    // Short inputLength (64 bytes = G1 + zero-padded Infinity) starting at offset 37
    Arrays.fill(out, (byte) 0);
    assertThat(Bn254Add.ecAdd(backing, 37, 64, out)).isTrue();
    assertThat(Bytes.wrap(out)).isEqualTo(encodePoint(g));
  }

  @Test
  void shouldRejectOutOfRangeCoordinatesAndInvalidCurvePoints() {
    final BigInteger gX = BigInteger.ONE;
    final BigInteger gY = BigInteger.TWO;
    final BigInteger zero = BigInteger.ZERO;

    final List<BigInteger> badCoords =
        List.of(
            Q,
            Q.add(BigInteger.ONE),
            Q.add(BigInteger.TWO),
            BigInteger.ONE.shiftLeft(254).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));

    for (final BigInteger bad : badCoords) {
      // Bad coordinate in P1 with P2 = Inf or G1
      assertThat(fastAdd(encodeCoords(bad, gY, zero, zero))).isNull();
      assertThat(fastAdd(encodeCoords(gX, bad, zero, zero))).isNull();
      assertThat(fastAdd(encodeCoords(zero, bad, zero, zero))).isNull();
      assertThat(fastAdd(encodeCoords(bad, zero, zero, zero))).isNull();
      assertThat(fastAdd(encodeCoords(bad, gY, gX, gY))).isNull();
      assertThat(fastAdd(encodeCoords(gX, bad, gX, gY))).isNull();

      // Bad coordinate in P2 with P1 = Inf or G1
      assertThat(fastAdd(encodeCoords(zero, zero, bad, gY))).isNull();
      assertThat(fastAdd(encodeCoords(zero, zero, gX, bad))).isNull();
      assertThat(fastAdd(encodeCoords(zero, zero, zero, bad))).isNull();
      assertThat(fastAdd(encodeCoords(zero, zero, bad, zero))).isNull();
      assertThat(fastAdd(encodeCoords(gX, gY, bad, gY))).isNull();
      assertThat(fastAdd(encodeCoords(gX, gY, gX, bad))).isNull();
    }

    // Points with coordinates < q that are NOT on y^2 = x^3 + 3
    final BigInteger[][] invalidPoints = {
      {zero, BigInteger.ONE},
      {zero, BigInteger.TWO},
      {zero, BigInteger.valueOf(3)},
      {zero, Q.subtract(BigInteger.ONE)},
      {BigInteger.ONE, zero},
      {BigInteger.ONE, BigInteger.ONE},
      {BigInteger.ONE, BigInteger.valueOf(3)},
      {BigInteger.ONE, Q.subtract(BigInteger.ONE)},
      {BigInteger.TWO, BigInteger.valueOf(3)},
      {BigInteger.valueOf(6), BigInteger.valueOf(9)},
      // Same X as G1 (1), but Y != 2 and Y != q - 2
      {BigInteger.ONE, BigInteger.valueOf(4)},
      {BigInteger.ONE, Q.subtract(BigInteger.valueOf(3))}
    };

    for (final BigInteger[] pt : invalidPoints) {
      final BigInteger px = pt[0];
      final BigInteger py = pt[1];
      // Invalid P1 + Inf
      assertThat(fastAdd(encodeCoords(px, py, zero, zero))).isNull();
      // Inf + Invalid P2
      assertThat(fastAdd(encodeCoords(zero, zero, px, py))).isNull();
      // Invalid P1 + G1
      assertThat(fastAdd(encodeCoords(px, py, gX, gY))).isNull();
      // G1 + Invalid P2
      assertThat(fastAdd(encodeCoords(gX, gY, px, py))).isNull();
      // Invalid P1 + Invalid P1 (doubling branch!)
      assertThat(fastAdd(encodeCoords(px, py, px, py))).isNull();
      // (badX, 2) + G1
      if (!px.equals(gX)) {
        assertThat(fastAdd(encodeCoords(px, gY, gX, gY))).isNull();
      }
      // G1 + (1, badY) (same X as P1, different Y, where Y1 + Y2 != q)
      if (!py.equals(gY)) {
        assertThat(fastAdd(encodeCoords(gX, gY, gX, py))).isNull();
      }
    }
  }

  @Test
  void shouldVerifyGroupLawAndDifferentialEquivalenceAcrossCurvePoints() {
    final AltBn128Point inf = new AltBn128Point(Fq.zero(), Fq.zero());
    final AltBn128Point g = AltBn128Point.g1();

    // Generate diverse valid points on the curve: k * G1 and their negations -k * G1
    final List<AltBn128Point> points = new ArrayList<>();
    points.add(inf);
    final BigInteger[] scalars = {
      BigInteger.ONE,
      BigInteger.TWO,
      BigInteger.valueOf(3),
      BigInteger.valueOf(4),
      BigInteger.valueOf(5),
      BigInteger.valueOf(7),
      BigInteger.valueOf(42),
      BigInteger.valueOf(9935),
      new BigInteger("1581d9d4d7eb0e3cdc75739f7098479097d579573f23e70b07cbd40a5d97bbc1", 16),
      new BigInteger("2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8", 16)
    };
    for (final BigInteger k : scalars) {
      final AltBn128Point p = g.multiply(k);
      points.add(p);
      // Note: Fq.negate() returns negative BigInteger (-y), so reduce mod Q for canonical [0, q-1]
      points.add(new AltBn128Point(p.getX(), Fq.zero().subtract(p.getY())));
    }

    // All pairs (P, Q): verify fastAdd(P, Q) == legacyAdd(P, Q) and commutativity P + Q == Q + P
    for (final AltBn128Point p : points) {
      final Bytes pBytes = encodePoint(p);
      for (final AltBn128Point q : points) {
        final Bytes qBytes = encodePoint(q);
        final Bytes pqInput = Bytes.concatenate(pBytes, qBytes);
        final Bytes qpInput = Bytes.concatenate(qBytes, pBytes);

        final Bytes expected = legacyAdd(pqInput);
        final Bytes actualPQ = fastAdd(pqInput);
        final Bytes actualQP = fastAdd(qpInput);

        assertThat(actualPQ).isNotNull().isEqualTo(expected).isEqualTo(actualQP);
      }
    }

    // Verify associativity: (P + Q) + R == P + (Q + R)
    for (int i = 0; i < 8; i++) {
      final Bytes p = encodePoint(points.get(i));
      final Bytes q = encodePoint(points.get(i + 2));
      final Bytes r = encodePoint(points.get(i + 5));

      final Bytes pq = fastAdd(Bytes.concatenate(p, q));
      final Bytes pq_r = fastAdd(Bytes.concatenate(pq, r));

      final Bytes qr = fastAdd(Bytes.concatenate(q, r));
      final Bytes p_qr = fastAdd(Bytes.concatenate(p, qr));

      assertThat(pq_r).isEqualTo(p_qr);
    }
  }

  @Test
  void shouldHandleAllInputLengthsFrom0To256Bytes() {
    final AltBn128Point g = AltBn128Point.g1();
    final AltBn128Point g3 = g.multiply(BigInteger.valueOf(3));
    final Bytes valid128 = Bytes.concatenate(encodePoint(g), encodePoint(g3));

    // Test every truncation length 0..128 of valid128 against legacyAdd
    for (int len = 0; len <= 128; len++) {
      final Bytes truncated = valid128.slice(0, len);
      assertThat(fastAdd(truncated)).as("truncated len=%d", len).isEqualTo(legacyAdd(truncated));
    }

    // Test every overlong length 129..256 (with non-zero trailing bytes)
    final MutableBytes overlong = MutableBytes.create(256);
    overlong.fill((byte) 0xA5);
    valid128.copyTo(overlong, 0);
    final Bytes expectedFull = legacyAdd(valid128);
    for (int len = 129; len <= 256; len++) {
      assertThat(fastAdd(overlong.slice(0, len)))
          .as("overlong len=%d", len)
          .isEqualTo(expectedFull);
    }
  }

  @Test
  void shouldMatchLegacyOnAllBenchmarkCasesAndEdgeCases() {
    final Map<String, Bytes> cases = getBenchmarkCases();
    // Point doubling (G1 + G1)
    cases.put(
        "DoubleG1",
        Bytes.fromHexString(
            "0000000000000000000000000000000000000000000000000000000000000001"
                + "0000000000000000000000000000000000000000000000000000000000000002"
                + "0000000000000000000000000000000000000000000000000000000000000001"
                + "0000000000000000000000000000000000000000000000000000000000000002"));
    // Point + Infinity
    cases.put(
        "G1PlusInf",
        Bytes.fromHexString(
            "0000000000000000000000000000000000000000000000000000000000000001"
                + "0000000000000000000000000000000000000000000000000000000000000002"));
    // Empty input
    cases.put("Empty", Bytes.EMPTY);
    // Invalid curve point (1, 3) + (0, 0)
    cases.put(
        "InvalidPoint",
        Bytes.fromHexString(
            "0000000000000000000000000000000000000000000000000000000000000001"
                + "0000000000000000000000000000000000000000000000000000000000000003"));

    for (final Map.Entry<String, Bytes> entry : cases.entrySet()) {
      final Bytes expected = legacyAdd(entry.getValue());
      final Bytes actual = fastAdd(entry.getValue());
      assertThat(actual).as(entry.getKey()).isEqualTo(expected);
    }
  }

  private static Map<String, Bytes> getBenchmarkCases() {
    final Map<String, Bytes> cases = new LinkedHashMap<>();
    cases.put(
        "EcAdd (P + -P)",
        Bytes.fromHexString(
            "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "01e0559bacb160664764a357af8a9fe70baa9258e0b959273ffc5718c6d4cc7c"
                + "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "2e83f8d734803fc370eba25ed1f6b8768bd6d83887b87165fc2434fe11a830cb"));
    cases.put(
        "EcAddMarius",
        Bytes.fromHexString(
            "1581d9d4d7eb0e3cdc75739f7098479097d579573f23e70b07cbd40a"
                + "5d97bbc118da69b1d2cb7b89fdb3bba2524bf135453ce828faea190d8f4d48d572cbe3fe20"
                + "34b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f"
                + "5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d460"));
    cases.put(
        "EcAddAmez1",
        Bytes.fromHexString(
            "1f69350cfea1bdf51ecec3a0bdf57732411418502904eb86c10901af310b9ca0291547261fc8bbcf961534f3f3d282d4ab3bfa0b9f3b5ecb3e575d42fd13e25c"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));
    cases.put(
        "EcAddAmez2",
        Bytes.fromHexString(
            "03adc0948243eac87e40fd1d4a3b5693c651abd30a88b93f3dbe3f571791c4120e9f7fa4c9ae503399c7a4f0ee0e91961b759d94e49c8ea0d04b448dbc6f3d16"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));
    cases.put(
        "EcAddAmez3",
        Bytes.fromHexString(
            "012b7638324563dc328b870d414807ed27426354bc83f22c42690f717ae7137e19dffc74cd0183d631cb39f5f58d2846744119913519373ebb95f2654a989390"
                + "2034b5942fbdd612f2553a9fd9fa5eb4c3e5ce6a34ed100f62de0e380f4e67f8016027893e1f5082bdc48651667776e2b1e32a85edd33ff430eef15e8e68b3d4"));
    cases.put(
        "EcDouble",
        Bytes.fromHexString(
            "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "01e0559bacb160664764a357af8a9fe70baa9258e0b959273ffc5718c6d4cc7c"
                + "17c139df0efee0f766bc0204762b774362e4ded88953a39ce849a8a7fa163fa9"
                + "01e0559bacb160664764a357af8a9fe70baa9258e0b959273ffc5718c6d4cc7c"));
    return cases;
  }

  @Test
  void benchmarkLegacyVsNew() {
    final Map<String, Bytes> cases = getBenchmarkCases();
    final int warmup = 25_000;
    final int iterations = 50_000;

    // Microbenchmark montInvStdToMont and montMul
    final long[] benchS = new long[32];
    fromBigInt(
        new BigInteger("1581d9d4d7eb0e3cdc75739f7098479097d579573f23e70b07cbd40a5d97bbc1", 16),
        benchS,
        0);
    Bn254Add.toMont(benchS, 0, benchS, 12);
    for (int i = 0; i < warmup; i++) {
      Bn254Add.montMul(benchS, 12, benchS, 12, benchS, 4);
      Bn254Add.montInvStdToMont(benchS, 0, benchS, 8);
    }
    long tm0 = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Bn254Add.montMul(benchS, 12, benchS, 4, benchS, 4);
    }
    long mulNs = (System.nanoTime() - tm0) / iterations;
    long ti0 = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      Bn254Add.montInvStdToMont(benchS, 0, benchS, 8);
    }
    long invNs = (System.nanoTime() - ti0) / iterations;
    System.out.printf("Microbench: montMul = %d ns, montInvStdToMont = %d ns%n", mulNs, invNs);

    System.out.println(
        "\n==================== ECADD BENCHMARK (150 Gas, Target 100 MGas/s) ====================");
    System.out.printf(
        "%-18s | %14s | %14s | %10s | %12s | %12s%n",
        "Test Case", "Existing (ns)", "New Limb (ns)", "Speedup", "Existing MG/s", "New MG/s");
    System.out.println(
        "------------------------------------------------------------------------------------------");

    for (final Map.Entry<String, Bytes> entry : cases.entrySet()) {
      final Bytes input = entry.getValue();

      // Warmup both
      for (int i = 0; i < warmup; i++) {
        legacyAdd(input);
        fastAdd(input);
      }

      // Measure Existing
      final long t0 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        legacyAdd(input);
      }
      final long legacyNs = (System.nanoTime() - t0) / iterations;

      // Measure New
      final long t1 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        fastAdd(input);
      }
      final long fastNs = (System.nanoTime() - t1) / iterations;

      final double speedup = (double) legacyNs / fastNs;
      final double legacyMgps = 150_000.0 / legacyNs;
      final double fastMgps = 150_000.0 / fastNs;

      System.out.printf(
          "%-18s | %,11d ns | %,11d ns | %9.2fx | %9.2f MG/s | %9.2f MG/s%n",
          entry.getKey(), legacyNs, fastNs, speedup, legacyMgps, fastMgps);
    }
    System.out.println(
        "==========================================================================================\n");
  }
}

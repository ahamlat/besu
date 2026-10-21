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
package org.hyperledger.besu.evm.precompile;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.altbn128.AltBn128Point;
import org.hyperledger.besu.crypto.altbn128.FieldElement;
import org.hyperledger.besu.crypto.altbn128.Fq;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AltBN128AddPrecompiledContractTest {

  private static final BigInteger Q = FieldElement.FIELD_MODULUS;

  @Mock MessageFrame messageFrame;

  private final PrecompiledContract istanbulContract =
      EvmSpec.evmSpec(EvmSpecVersion.ISTANBUL)
          .getPrecompileContractRegistry()
          .get(Address.ALTBN128_ADD);

  @AfterEach
  void restoreDefaults() {
    AbstractAltBnPrecompiledContract.maybeEnableNative();
    AbstractPrecompiledContract.setPrecompileCaching(false);
  }

  private static Bytes computeLegacyBigInteger(final Bytes input) {
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

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldMatchNativeAndLegacyAcrossAllVectors(final boolean useNative) {
    AbstractPrecompiledContract.setPrecompileCaching(false);
    if (useNative) {
      AbstractAltBnPrecompiledContract.maybeEnableNative();
    } else {
      AbstractAltBnPrecompiledContract.disableNative();
    }

    for (final Map.Entry<String, Bytes> entry : getBenchmarkCases().entrySet()) {
      final Bytes expected = computeLegacyBigInteger(entry.getValue());
      final PrecompileContractResult result =
          istanbulContract.computePrecompile(entry.getValue(), messageFrame);
      assertThat(result.state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
      assertThat(result.output()).as(entry.getKey()).isEqualTo(expected);
    }
  }

  @Test
  void shouldVerifyDifferentialParityBetweenNativeNewJavaAndOldJavaAcrossReferenceVectors() {
    AbstractPrecompiledContract.setPrecompileCaching(false);
    final boolean nativeAvailable = AbstractAltBnPrecompiledContract.maybeEnableNative();

    final BigInteger zero = BigInteger.ZERO;
    final BigInteger one = BigInteger.ONE;
    final BigInteger two = BigInteger.TWO;
    final BigInteger three = BigInteger.valueOf(3);
    final BigInteger x1145 =
        new BigInteger("0cd5320096a7bd2288a77002b202f7a0b6c61e22f89071a83857d01f077f1145", 16);
    final BigInteger y3932 =
        new BigInteger("1163a84a851225785d4083135147722552509300a7f6b29a2f3269a8d3c13932", 16);
    final BigInteger y4651 = Q.subtract(y3932);
    final BigInteger x2969 =
        new BigInteger("1c3489056c29d04f47f0a69e4866006713972c4a6267d620202e5e2e8b022969", 16);
    final BigInteger y1336 =
        new BigInteger("0e39d89166bf8ff4fa3062d028fa487538e2ca785cb16e0002d59a010aa81336", 16);

    final List<Bytes> vectors = new ArrayList<>();
    // Valid reference test vectors (stZeroKnowledge & stZeroKnowledge2 & execution-specs)
    vectors.add(Bytes.EMPTY);
    vectors.add(Bytes.repeat((byte) 0, 64));
    vectors.add(Bytes.repeat((byte) 0, 80));
    vectors.add(Bytes.repeat((byte) 0, 127));
    vectors.add(Bytes.repeat((byte) 0, 128));
    vectors.add(Bytes.repeat((byte) 0, 129));
    vectors.add(Bytes.repeat((byte) 0, 192));
    vectors.add(encodeCoords(zero, zero, one, two));
    vectors.add(encodeCoords(one, two, zero, zero));
    vectors.add(encodeCoords(one, two, zero, zero).slice(0, 64));
    vectors.add(encodeCoords(one, two, one, two));
    vectors.add(encodeCoords(one, two, one, Q.subtract(two)));
    vectors.add(encodeCoords(one, Q.subtract(two), one, two));
    vectors.add(encodeCoords(one, Q.subtract(two), one, Q.subtract(two)));
    vectors.add(encodeCoords(x1145, y3932, x2969, y1336));
    vectors.add(encodeCoords(x1145, y3932, x1145, y4651));
    // Overlong with trailing invalid point (1, 3) after 128 bytes (must succeed and ignore bytes >=
    // 128)
    vectors.add(
        Bytes.concatenate(encodeCoords(one, two, one, two), encodeCoords(one, three, zero, zero)));
    // Sliced view with non-zero prefix and suffix
    final Bytes wrapped =
        Bytes.concatenate(
            Bytes.repeat((byte) 0x7F, 32),
            encodeCoords(x1145, y3932, x2969, y1336),
            Bytes.repeat((byte) 0x7F, 64));
    vectors.add(wrapped.slice(32, 128));
    vectors.add(wrapped.slice(32, 192));

    // Invalid reference test vectors (must halt with PRECOMPILE_ERROR)
    vectors.add(encodeCoords(zero, three, one, two));
    vectors.add(encodeCoords(zero, zero, one, three));
    vectors.add(encodeCoords(one, three, zero, zero).slice(0, 64));
    vectors.add(encodeCoords(one, three, zero, zero).slice(0, 65));
    vectors.add(encodeCoords(one, three, zero, zero).slice(0, 80));
    vectors.add(encodeCoords(one, three, zero, zero).slice(0, 96));
    vectors.add(encodeCoords(one, one, zero, zero));
    vectors.add(encodeCoords(zero, Q, zero, zero));
    vectors.add(encodeCoords(Q, zero, zero, zero));
    vectors.add(encodeCoords(one, Q.add(two), zero, zero));
    vectors.add(encodeCoords(Q.add(one), two, zero, zero));
    vectors.add(encodeCoords(zero, zero, zero, Q));
    vectors.add(encodeCoords(zero, zero, Q, zero));
    vectors.add(encodeCoords(zero, zero, one, Q.add(two)));
    vectors.add(encodeCoords(zero, zero, Q.add(one), two));
    vectors.add(
        encodeCoords(
            BigInteger.valueOf(6),
            BigInteger.valueOf(9),
            BigInteger.valueOf(19274124),
            BigInteger.valueOf(124124)));

    for (final Bytes input : vectors) {
      final Bytes legacyOut = computeLegacyBigInteger(input);

      AbstractAltBnPrecompiledContract.disableNative();
      final PrecompileContractResult newJavaRes =
          istanbulContract.computePrecompile(input, messageFrame);

      if (legacyOut == null) {
        assertThat(newJavaRes.state()).isEqualTo(MessageFrame.State.EXCEPTIONAL_HALT);
        assertThat(newJavaRes.haltReason()).contains(ExceptionalHaltReason.PRECOMPILE_ERROR);
        assertThat(newJavaRes.output()).isNull();
      } else {
        assertThat(newJavaRes.state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
        assertThat(newJavaRes.output()).isEqualTo(legacyOut);
      }

      if (nativeAvailable) {
        AbstractAltBnPrecompiledContract.maybeEnableNative();
        final PrecompileContractResult nativeRes =
            istanbulContract.computePrecompile(input, messageFrame);
        assertThat(nativeRes.state()).isEqualTo(newJavaRes.state());
        assertThat(nativeRes.haltReason()).isEqualTo(newJavaRes.haltReason());
        assertThat(nativeRes.output()).isEqualTo(newJavaRes.output());
      }
    }
  }

  @Test
  void shouldSupportResultCachingAndLargeSlicedInputsOnNewJava() {
    AbstractAltBnPrecompiledContract.disableNative();
    AbstractPrecompiledContract.setPrecompileCaching(true);

    // 1.6 MB backing array sliced with different trailing bytes beyond 128 bytes
    final byte[] backing = new byte[1_600_000];
    backing[31] = 1;
    backing[63] = 2;
    backing[95] = 1;
    backing[127] = 2;
    backing[200] = 0x42;

    final Bytes slice1 = Bytes.wrap(backing).slice(0, 800_000);
    final PrecompileContractResult res1 = istanbulContract.computePrecompile(slice1, messageFrame);
    assertThat(res1.state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    assertThat(res1.output()).isEqualTo(computeLegacyBigInteger(slice1.slice(0, 128)));

    // Second call with different bytes past 128 should hit cache and return identical result
    backing[200] = 0x7F;
    final Bytes slice2 = Bytes.wrap(backing).slice(0, 900_000);
    final PrecompileContractResult res2 = istanbulContract.computePrecompile(slice2, messageFrame);
    assertThat(res2.state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    assertThat(res2.output()).isEqualTo(res1.output());
  }

  @Test
  void benchmarkPrecompileExistingVsNewJava() {
    AbstractPrecompiledContract.setPrecompileCaching(false);
    final boolean nativeAvailable = AbstractAltBnPrecompiledContract.maybeEnableNative();

    final Map<String, Bytes> cases = getBenchmarkCases();
    final int warmup = 20_000;
    final int iterations = 40_000;

    System.out.println(
        "\n==================== PRECOMPILE ECADD BENCHMARK (150 Gas, Target 100 MGas/s) ====================");
    System.out.printf(
        "%-16s | %13s | %13s | %13s | %11s | %11s | %11s%n",
        "Test Case",
        "Native Gnark",
        "Old Java BigI",
        "New Java Limb",
        "Native MG/s",
        "OldJava MG/s",
        "NewJava MG/s");
    System.out.println(
        "----------------------------------------------------------------------------------------------------------");

    for (final Map.Entry<String, Bytes> entry : cases.entrySet()) {
      final Bytes input = entry.getValue();

      // Warmup
      for (int i = 0; i < warmup; i++) {
        computeLegacyBigInteger(input);
        if (nativeAvailable) {
          AbstractAltBnPrecompiledContract.maybeEnableNative();
          istanbulContract.computePrecompile(input, messageFrame);
        }
        AbstractAltBnPrecompiledContract.disableNative();
        istanbulContract.computePrecompile(input, messageFrame);
      }

      // 1. Native Gnark (JNA)
      long nativeNs = -1;
      if (nativeAvailable) {
        AbstractAltBnPrecompiledContract.maybeEnableNative();
        final long tn = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
          istanbulContract.computePrecompile(input, messageFrame);
        }
        nativeNs = (System.nanoTime() - tn) / iterations;
      }

      // 2. Old Java (BigInteger / Fq)
      final long t0 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        computeLegacyBigInteger(input);
      }
      final long legacyNs = (System.nanoTime() - t0) / iterations;

      // 3. New Java (4x64-bit Limbs / VarHandle)
      AbstractAltBnPrecompiledContract.disableNative();
      final long t1 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        istanbulContract.computePrecompile(input, messageFrame);
      }
      final long newNs = (System.nanoTime() - t1) / iterations;

      final double nativeMgps = nativeNs > 0 ? 150_000.0 / nativeNs : 0.0;
      final double legacyMgps = 150_000.0 / legacyNs;
      final double newMgps = 150_000.0 / newNs;

      System.out.printf(
          "%-16s | %,10d ns | %,10d ns | %,10d ns | %8.2f MG/s | %8.2f MG/s | %8.2f MG/s%n",
          entry.getKey(), nativeNs, legacyNs, newNs, nativeMgps, legacyMgps, newMgps);
    }
    System.out.println(
        "==========================================================================================================\n");
  }
}

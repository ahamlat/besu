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
package org.hyperledger.besu.evm.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.SpuriousDragonGasCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * Exhaustive correctness tests comparing the original (Tuweni-based) AND, OR, XOR, NOT operations
 * against the optimized (VarHandle long-register-based) versions.
 */
class BitwiseOperationsOptimizedTest {

  private static final GasCalculator GAS_CALCULATOR = new SpuriousDragonGasCalculator();

  private static final String ZERO =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
  private static final String MAX =
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
  private static final String HIGH_BIT =
      "0x8000000000000000000000000000000000000000000000000000000000000000";
  private static final String LOW_BIT =
      "0x0000000000000000000000000000000000000000000000000000000000000001";
  private static final String ALTERNATING_10 =
      "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String ALTERNATING_01 =
      "0x5555555555555555555555555555555555555555555555555555555555555555";
  private static final String WORD_BOUNDARY =
      "0xffffffffffffffff0000000000000000ffffffffffffffff0000000000000000";
  private static final String SINGLE_BYTE =
      "0x00000000000000000000000000000000000000000000000000000000000000ff";

  static List<String> testValues() {
    List<String> values = new ArrayList<>();
    values.add(ZERO);
    values.add(MAX);
    values.add(HIGH_BIT);
    values.add(LOW_BIT);
    values.add(ALTERNATING_10);
    values.add(ALTERNATING_01);
    values.add(WORD_BOUNDARY);
    values.add(SINGLE_BYTE);
    values.add("0x0f");
    values.add("0xff00");
    values.add("0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    values.add("0xdeadbeefcafebabe");
    values.add("0x80000000000000000000000000000000");

    Random rng = new Random(42);
    for (int i = 0; i < 20; i++) {
      byte[] b = new byte[32];
      rng.nextBytes(b);
      values.add(Bytes.wrap(b).toHexString());
    }
    return values;
  }

  static Stream<Arguments> binaryOperandPairs() {
    List<String> vals = testValues();
    List<Arguments> args = new ArrayList<>();
    for (String a : vals) {
      for (String b : vals) {
        args.add(Arguments.of(a, b));
      }
    }
    return args.stream();
  }

  static Stream<Arguments> unaryOperands() {
    return testValues().stream().map(Arguments::of);
  }

  // --- AND ---

  @ParameterizedTest(name = "AND({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void andOriginalMatchesOptimized(final String hexA, final String hexB) {
    Bytes resultOriginal = executeBinaryOriginal(hexA, hexB, "AND");
    Bytes resultOptimized = executeBinaryOptimized(hexA, hexB, "AND");
    assertThat(resultOptimized)
        .as("AND(%s, %s)", hexA, hexB)
        .isEqualTo(resultOriginal);
  }

  // --- OR ---

  @ParameterizedTest(name = "OR({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void orOriginalMatchesOptimized(final String hexA, final String hexB) {
    Bytes resultOriginal = executeBinaryOriginal(hexA, hexB, "OR");
    Bytes resultOptimized = executeBinaryOptimized(hexA, hexB, "OR");
    assertThat(resultOptimized)
        .as("OR(%s, %s)", hexA, hexB)
        .isEqualTo(resultOriginal);
  }

  // --- XOR ---

  @ParameterizedTest(name = "XOR({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void xorOriginalMatchesOptimized(final String hexA, final String hexB) {
    Bytes resultOriginal = executeBinaryOriginal(hexA, hexB, "XOR");
    Bytes resultOptimized = executeBinaryOptimized(hexA, hexB, "XOR");
    assertThat(resultOptimized)
        .as("XOR(%s, %s)", hexA, hexB)
        .isEqualTo(resultOriginal);
  }

  // --- NOT ---

  @ParameterizedTest(name = "NOT({0})")
  @MethodSource("unaryOperands")
  void notOriginalMatchesOptimized(final String hexA) {
    Bytes resultOriginal = executeUnaryOriginal(hexA);
    Bytes resultOptimized = executeUnaryOptimized(hexA);
    assertThat(resultOptimized)
        .as("NOT(%s)", hexA)
        .isEqualTo(resultOriginal);
  }

  // --- Algebraic property tests ---

  @ParameterizedTest(name = "AND_commutative({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void andIsCommutative(final String hexA, final String hexB) {
    Bytes ab = executeBinaryOptimized(hexA, hexB, "AND");
    Bytes ba = executeBinaryOptimized(hexB, hexA, "AND");
    assertThat(ab).isEqualTo(ba);
  }

  @ParameterizedTest(name = "OR_commutative({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void orIsCommutative(final String hexA, final String hexB) {
    Bytes ab = executeBinaryOptimized(hexA, hexB, "OR");
    Bytes ba = executeBinaryOptimized(hexB, hexA, "OR");
    assertThat(ab).isEqualTo(ba);
  }

  @ParameterizedTest(name = "XOR_commutative({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void xorIsCommutative(final String hexA, final String hexB) {
    Bytes ab = executeBinaryOptimized(hexA, hexB, "XOR");
    Bytes ba = executeBinaryOptimized(hexB, hexA, "XOR");
    assertThat(ab).isEqualTo(ba);
  }

  @ParameterizedTest(name = "XOR_self_is_zero({0})")
  @MethodSource("unaryOperands")
  void xorSelfIsZero(final String hexA) {
    Bytes result = executeBinaryOptimized(hexA, hexA, "XOR");
    assertThat(result).isEqualTo(Bytes.fromHexString(ZERO));
  }

  @ParameterizedTest(name = "NOT_NOT_is_identity({0})")
  @MethodSource("unaryOperands")
  void notNotIsIdentity(final String hexA) {
    Bytes notA = executeUnaryOptimized(hexA);
    Bytes notNotA = executeUnaryOptimized(notA.toHexString());
    assertThat(Bytes32.leftPad(notNotA))
        .isEqualTo(Bytes32.leftPad(Bytes.fromHexString(hexA)));
  }

  @ParameterizedTest(name = "AND_with_zero({0})")
  @MethodSource("unaryOperands")
  void andWithZeroIsZero(final String hexA) {
    Bytes result = executeBinaryOptimized(hexA, ZERO, "AND");
    assertThat(result).isEqualTo(Bytes.fromHexString(ZERO));
  }

  @ParameterizedTest(name = "OR_with_zero({0})")
  @MethodSource("unaryOperands")
  void orWithZeroIsIdentity(final String hexA) {
    Bytes result = executeBinaryOptimized(hexA, ZERO, "OR");
    assertThat(Bytes32.leftPad(result))
        .isEqualTo(Bytes32.leftPad(Bytes.fromHexString(hexA)));
  }

  @ParameterizedTest(name = "DeMorgan_AND({0}, {1})")
  @MethodSource("binaryOperandPairs")
  void deMorganAnd(final String hexA, final String hexB) {
    // NOT(A AND B) == NOT(A) OR NOT(B)
    Bytes andResult = executeBinaryOptimized(hexA, hexB, "AND");
    Bytes notAndResult = executeUnaryOptimized(andResult.toHexString());

    Bytes notA = executeUnaryOptimized(hexA);
    Bytes notB = executeUnaryOptimized(hexB);
    Bytes orNotResult = executeBinaryOptimized(notA.toHexString(), notB.toHexString(), "OR");

    assertThat(Bytes32.leftPad(notAndResult)).isEqualTo(Bytes32.leftPad(orNotResult));
  }

  @Test
  void dryRunDetector() {
    assertThat(true)
        .withFailMessage("This test is here so gradle --dry-run executes this class")
        .isTrue();
  }

  // --- Helpers ---

  private static Bytes executeBinaryOriginal(
      final String hexA, final String hexB, final String op) {
    MessageFrame frame = mock(MessageFrame.class);
    when(frame.stackSize()).thenReturn(2);
    when(frame.getRemainingGas()).thenReturn(100L);
    when(frame.popStackItem())
        .thenReturn(Bytes.fromHexString(hexA))
        .thenReturn(Bytes.fromHexString(hexB));

    switch (op) {
      case "AND" -> new AndOperation(GAS_CALCULATOR).execute(frame, null);
      case "OR" -> new OrOperation(GAS_CALCULATOR).execute(frame, null);
      case "XOR" -> new XorOperation(GAS_CALCULATOR).execute(frame, null);
      default -> throw new IllegalArgumentException("Unknown op: " + op);
    }

    ArgumentCaptor<Bytes> captor = ArgumentCaptor.forClass(Bytes.class);
    verify(frame).pushStackItem(captor.capture());
    return Bytes32.leftPad(captor.getValue());
  }

  private static Bytes executeBinaryOptimized(
      final String hexA, final String hexB, final String op) {
    MessageFrame frame = mock(MessageFrame.class);
    when(frame.stackSize()).thenReturn(2);
    when(frame.getRemainingGas()).thenReturn(100L);
    when(frame.popStackItem())
        .thenReturn(Bytes.fromHexString(hexA))
        .thenReturn(Bytes.fromHexString(hexB));

    switch (op) {
      case "AND" -> new AndOperationOptimized(GAS_CALCULATOR).execute(frame, null);
      case "OR" -> new OrOperationOptimized(GAS_CALCULATOR).execute(frame, null);
      case "XOR" -> new XorOperationOptimized(GAS_CALCULATOR).execute(frame, null);
      default -> throw new IllegalArgumentException("Unknown op: " + op);
    }

    ArgumentCaptor<Bytes> captor = ArgumentCaptor.forClass(Bytes.class);
    verify(frame).pushStackItem(captor.capture());
    return Bytes32.leftPad(captor.getValue());
  }

  private static Bytes executeUnaryOriginal(final String hexA) {
    MessageFrame frame = mock(MessageFrame.class);
    when(frame.stackSize()).thenReturn(1);
    when(frame.getRemainingGas()).thenReturn(100L);
    when(frame.popStackItem()).thenReturn(Bytes.fromHexString(hexA));

    new NotOperation(GAS_CALCULATOR).execute(frame, null);

    ArgumentCaptor<Bytes> captor = ArgumentCaptor.forClass(Bytes.class);
    verify(frame).pushStackItem(captor.capture());
    return Bytes32.leftPad(captor.getValue());
  }

  private static Bytes executeUnaryOptimized(final String hexA) {
    MessageFrame frame = mock(MessageFrame.class);
    when(frame.stackSize()).thenReturn(1);
    when(frame.getRemainingGas()).thenReturn(100L);
    when(frame.popStackItem()).thenReturn(Bytes.fromHexString(hexA));

    new NotOperationOptimized(GAS_CALCULATOR).execute(frame, null);

    ArgumentCaptor<Bytes> captor = ArgumentCaptor.forClass(Bytes.class);
    verify(frame).pushStackItem(captor.capture());
    return Bytes32.leftPad(captor.getValue());
  }
}

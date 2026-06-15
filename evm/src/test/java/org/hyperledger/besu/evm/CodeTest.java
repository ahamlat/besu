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

import static org.hyperledger.besu.evm.frame.MessageFrame.Type.MESSAGE_CALL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.JumpOperation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Random;

import jakarta.validation.constraints.NotNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CodeTest {

  private static final int CURRENT_PC = 1;
  private EVM evm;

  @BeforeEach
  void startUp() {
    evm = MainnetEVMs.futureEips(EvmConfiguration.DEFAULT);
  }

  @Test
  void shouldReuseJumpDestMap() {
    final JumpOperation operation = new JumpOperation(evm.getGasCalculator());
    final Bytes jumpBytes = Bytes.fromHexString("0x6003565b00");
    final Code getsCached = spy(new Code(jumpBytes));
    MessageFrame frame = createJumpFrame(getsCached);

    OperationResult result = operation.execute(frame, evm);
    assertNull(result.getHaltReason());
    Mockito.verify(getsCached, times(1)).calculateJumpDestBitMask();

    // do it again to prove we don't recalculate, and we hit the cache

    frame = createJumpFrame(getsCached);

    result = operation.execute(frame, evm);
    assertNull(result.getHaltReason());
    Mockito.verify(getsCached, times(1)).calculateJumpDestBitMask();
  }

  /**
   * Independent, deliberately simple reference scan: walk opcodes from byte 0, mark every JUMPDEST
   * reached as an opcode (not as PUSH immediate data). Used to validate both the scalar
   * {@link Code#calculateJumpDestBitMask()} and the SIMD {@link Code#calculateJumpDestBitMaskVector()}
   * on adversarial and random inputs.
   */
  private static long[] referenceJumpDestBitMask(final byte[] code) {
    final long[] bitmap = new long[(code.length >> 6) + 1];
    int i = 0;
    while (i < code.length) {
      final int op = code[i] & 0xff;
      if (op == 0x5b) {
        bitmap[i >> 6] |= 1L << (i & 63);
      }
      if (op >= 0x60 && op <= 0x7f) {
        i += (op - 0x5f) + 1; // skip the opcode and its immediate data bytes
      } else {
        i++;
      }
    }
    return bitmap;
  }

  /** Asserts the scalar and SIMD jumpdest scans both equal the independent reference. */
  private static void assertJumpDestBitMask(final byte[] code) {
    final long[] expected = referenceJumpDestBitMask(code);
    final Code subject = new Code(Bytes.wrap(code));
    assertArrayEquals(
        expected, subject.calculateJumpDestBitMask(), () -> "scalar mismatch: " + hex(code));
    assertArrayEquals(
        expected, subject.calculateJumpDestBitMaskVector(), () -> "vector mismatch: " + hex(code));
  }

  private static String hex(final byte[] code) {
    return Bytes.wrap(code).toHexString();
  }

  @Test
  void jumpDestBitMaskMatchesReferenceForAdversarialContract() {
    // PUSH2 0x5FFF; JUMP; then 0x5b padding up to the last byte at 0x5FFF (EIP-170 max size).
    final byte[] code = new byte[0x6000];
    java.util.Arrays.fill(code, (byte) 0x5b);
    code[0] = 0x61; // PUSH2
    code[1] = 0x5f;
    code[2] = (byte) 0xff;
    code[3] = 0x56; // JUMP
    assertJumpDestBitMask(code);
  }

  @Test
  void jumpDestBitMaskMatchesReferenceForRandomBytecode() {
    final Random random = new Random(0xB16B00B5L); // fixed seed for reproducibility
    for (int trial = 0; trial < 50_000; trial++) {
      final int length = random.nextInt(300);
      final byte[] code = new byte[length];
      for (int k = 0; k < length; k++) {
        final double r = random.nextDouble();
        if (r < 0.4) {
          code[k] = 0x5b; // bias toward JUMPDEST to exercise the vector fast path
        } else if (r < 0.6) {
          code[k] = (byte) (0x60 + random.nextInt(32)); // PUSH1..PUSH32
        } else {
          code[k] = (byte) random.nextInt(256);
        }
      }
      assertJumpDestBitMask(code);
    }
  }

  @Test
  void jumpDestBitMaskMatchesReferenceAroundChunkBoundaries() {
    // Exercise lengths around 64-byte window and vector-lane edges with a JUMPDEST run.
    for (int length = 1; length <= 200; length++) {
      final byte[] code = new byte[length];
      java.util.Arrays.fill(code, (byte) 0x5b);
      assertJumpDestBitMask(code);
    }
  }

  @NotNull
  private MessageFrame createJumpFrame(final Code getsCached) {
    final MessageFrame frame =
        MessageFrame.builder()
            .type(MESSAGE_CALL)
            .worldUpdater(mock(WorldUpdater.class))
            .initialGas(10_000L)
            .address(Address.ZERO)
            .originator(Address.ZERO)
            .contract(Address.ZERO)
            .gasPrice(Wei.ZERO)
            .inputData(Bytes.EMPTY)
            .sender(Address.ZERO)
            .value(Wei.ZERO)
            .apparentValue(Wei.ZERO)
            .code(getsCached)
            .blockValues(mock(BlockValues.class))
            .completer(f -> {})
            .miningBeneficiary(Address.ZERO)
            .blockHashLookup((__, ___) -> Hash.EMPTY)
            .build();

    frame.setPC(CURRENT_PC);
    frame.pushStackItem(UInt256.fromHexString("0x03"));
    return frame;
  }
}

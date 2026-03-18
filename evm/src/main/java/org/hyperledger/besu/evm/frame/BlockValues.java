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
package org.hyperledger.besu.evm.frame;

import org.hyperledger.besu.datatypes.Wei;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Block Header Values used by various EVM Opcodes. This is not a complete BlockHeader, just the
 * values that are returned or accessed by various operations.
 */
public interface BlockValues {

  VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  /**
   * Returns the block difficulty.
   *
   * @return the block difficulty
   */
  default Bytes getDifficultyBytes() {
    return null;
  }

  /**
   * Returns the mixHash before merge, and the prevRandao value after
   *
   * @return the mixHash before merge, and the prevRandao value after
   */
  default Bytes32 getMixHashOrPrevRandao() {
    return null;
  }

  /**
   * Writes the 4 big-endian long limbs of the prevRandao/mixHash value into the target array at the
   * given offset. This enables zero-allocation transfer to the EVM operand stack.
   *
   * @param target the target long array (typically the EVM stack)
   * @param off the offset into the array where u3 should be written
   */
  default void writePrevRandaoLimbs(final long[] target, final int off) {
    final Bytes32 value = getMixHashOrPrevRandao();
    if (value == null) {
      target[off] = 0;
      target[off + 1] = 0;
      target[off + 2] = 0;
      target[off + 3] = 0;
    } else {
      final byte[] b = value.toArrayUnsafe();
      target[off] = (long) LONG_BE.get(b, 0);
      target[off + 1] = (long) LONG_BE.get(b, 8);
      target[off + 2] = (long) LONG_BE.get(b, 16);
      target[off + 3] = (long) LONG_BE.get(b, 24);
    }
  }

  /**
   * Returns the basefee of the block.
   *
   * @return the raw bytes of the extra data field
   */
  default Optional<Wei> getBaseFee() {
    return Optional.empty();
  }

  /**
   * Returns the block number.
   *
   * @return the block number
   */
  default long getNumber() {
    return 0L;
  }

  /**
   * Return the block timestamp.
   *
   * @return the block timestamp
   */
  default long getTimestamp() {
    return 0L;
  }

  /**
   * Return the block gas limit.
   *
   * @return the block gas limit
   */
  default long getGasLimit() {
    return 0L;
  }

  /**
   * Return the block slot number.
   *
   * @return the block slot number
   */
  default long getSlotNumber() {
    return 0L;
  }
}

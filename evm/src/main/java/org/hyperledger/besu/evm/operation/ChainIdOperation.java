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
package org.hyperledger.besu.evm.operation;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** The Chain id operation. */
public class ChainIdOperation extends AbstractFixedCostOperation {

  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  /** The CHAINID Opcode number */
  public static final int OPCODE = 0x46;

  private final Bytes32 chainId;
  private final long chainIdU3;
  private final long chainIdU2;
  private final long chainIdU1;
  private final long chainIdU0;

  /**
   * Instantiates a new Chain id operation.
   *
   * @param gasCalculator the gas calculator
   * @param chainId the chain id
   */
  public ChainIdOperation(final GasCalculator gasCalculator, final Bytes32 chainId) {
    super(OPCODE, "CHAINID", 0, 1, gasCalculator, gasCalculator.getBaseTierGasCost());
    this.chainId = chainId;
    final byte[] b = chainId.toArrayUnsafe();
    this.chainIdU3 = (long) LONG_BE.get(b, 0);
    this.chainIdU2 = (long) LONG_BE.get(b, 8);
    this.chainIdU1 = (long) LONG_BE.get(b, 16);
    this.chainIdU0 = (long) LONG_BE.get(b, 24);
  }

  /**
   * Returns the chain ID this operation uses
   *
   * @return then chainID;
   */
  public Bytes getChainId() {
    return chainId;
  }

  @Override
  public Operation.OperationResult executeFixedCostOperation(
      final MessageFrame frame, final EVM evm) {
    if (!frame.stackHasSpace(1)) return OVERFLOW_RESPONSE;
    final long[] s = frame.stackData();
    final int top = frame.stackTop();
    final int dst = top << 2;
    s[dst] = chainIdU3;
    s[dst + 1] = chainIdU2;
    s[dst + 2] = chainIdU1;
    s[dst + 3] = chainIdU0;
    frame.setTop(top + 1);
    return successResponse;
  }
}

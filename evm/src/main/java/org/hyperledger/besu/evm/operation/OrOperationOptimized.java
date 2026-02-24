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

import static org.apache.tuweni.bytes.Bytes32.leftPad;
import static org.hyperledger.besu.evm.operation.Bitwise256Operations.getLong;
import static org.hyperledger.besu.evm.operation.Bitwise256Operations.putLong;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import org.apache.tuweni.bytes.Bytes;

/** The Or operation. */
public class OrOperationOptimized extends AbstractFixedCostOperation {

  /** The Or operation success result. */
  static final OperationResult orSuccess = new OperationResult(3, null);

  /**
   * Instantiates a new Or operation.
   *
   * @param gasCalculator the gas calculator
   */
  public OrOperationOptimized(final GasCalculator gasCalculator) {
    super(0x17, "OR", 2, 1, gasCalculator, gasCalculator.getVeryLowTierGasCost());
  }

  @Override
  public OperationResult executeFixedCostOperation(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame);
  }

  /**
   * Performs OR using 4-long register-based computation to keep values in CPU cache.
   *
   * @param frame the frame
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame) {
    final byte[] a = leftPad(frame.popStackItem()).toArrayUnsafe();
    final byte[] b = leftPad(frame.popStackItem()).toArrayUnsafe();

    final byte[] out = new byte[32];
    putLong(out, 0, getLong(a, 0) | getLong(b, 0));
    putLong(out, 8, getLong(a, 8) | getLong(b, 8));
    putLong(out, 16, getLong(a, 16) | getLong(b, 16));
    putLong(out, 24, getLong(a, 24) | getLong(b, 24));

    frame.pushStackItem(Bytes.wrap(out));
    return orSuccess;
  }
}

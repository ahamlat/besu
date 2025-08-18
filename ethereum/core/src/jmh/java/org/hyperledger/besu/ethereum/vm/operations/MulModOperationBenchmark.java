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
package org.hyperledger.besu.ethereum.vm.operations;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.CountLeadingZerosOperation;
import org.hyperledger.besu.evm.operation.MulModOperation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

@State(Scope.Thread)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(value = TimeUnit.NANOSECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
public class MulModOperationBenchmark {
  private static final int OPERATIONS_PER_INVOCATION = 1_000_000;

  private static final String bytesHex1 = "0xf8a52a89d5c4e34f1b2e3d9c8a7b6540c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9";
  private static final String bytesHex2 = "0xe7d6c5b4a3928172635f4e3d2c1b0a99887766554433221100fffeeddccbba";
  private static final String bytesHex3 = "0xd6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6";

  private Bytes bytes1;
  private Bytes bytes2;
  private Bytes bytes3;


  private MessageFrame frame;

  @Setup
  public void setUp() {
    frame =
        MessageFrame.builder()
            .worldUpdater(mock(WorldUpdater.class))
            .originator(Address.ZERO)
            .gasPrice(Wei.ONE)
            .blobGasPrice(Wei.ONE)
            .blockValues(mock(BlockValues.class))
            .miningBeneficiary(Address.ZERO)
            .blockHashLookup((__, ___) -> Hash.ZERO)
            .type(MessageFrame.Type.MESSAGE_CALL)
            .initialGas(1)
            .address(Address.ZERO)
            .contract(Address.ZERO)
            .inputData(Bytes32.ZERO)
            .sender(Address.ZERO)
            .value(Wei.ZERO)
            .apparentValue(Wei.ZERO)
            .code(CodeV0.EMPTY_CODE)
            .completer(messageFrame -> {})
            .build();
    bytes1 = Bytes.fromHexString(bytesHex1);
    bytes2 = Bytes.fromHexString(bytesHex2);
    bytes3 = Bytes.fromHexString(bytesHex3);
  }

  @Benchmark
  @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
  public void executeOperation() {
    for (int i = 0; i < OPERATIONS_PER_INVOCATION; i++) {
      frame.pushStackItem(bytes1);
      frame.pushStackItem(bytes2);
      frame.pushStackItem(bytes3);
      MulModOperation.staticOperation(frame);
      frame.popStackItem();
    }
  }

  @Benchmark
  @OperationsPerInvocation(OPERATIONS_PER_INVOCATION)
  public void baseline() {
    for (int i = 0; i < OPERATIONS_PER_INVOCATION; i++) {
      frame.pushStackItem(bytes1);
      frame.pushStackItem(bytes2);
      frame.pushStackItem(bytes3);
      frame.popStackItem();
    }
  }
}

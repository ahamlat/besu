/*
 * Copyright ConsenSys AG.
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
import org.hyperledger.besu.evm.operation.Operation.OperationResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registry for EVM operations with MethodHandle support for performance optimization. */
public class OperationRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(OperationRegistry.class);

  private static final MethodType EXECUTE_SIGNATURE =
      MethodType.methodType(OperationResult.class, MessageFrame.class, EVM.class);

  private final Operation[] operations = new Operation[256];
  private MethodHandle[] operationMethodHandles;
  private final MethodHandles.Lookup lookup = MethodHandles.lookup();

  /** Default constructor */
  public OperationRegistry() {
    Arrays.fill(operations, null);
  }

  /**
   * Register an operation at its opcode position
   *
   * @param operation the operation to register
   */
  public void put(final Operation operation) {
    int opcode = operation.getOpcode();
    if (opcode < 0 || opcode > 255) {
      throw new IllegalArgumentException("Opcode must be between 0 and 255: " + opcode);
    }
    operations[opcode] = operation;
  }

  /**
   * Get an operation by opcode
   *
   * @param opcode the opcode
   * @return the operation, or null if not registered
   */
  public Operation get(final int opcode) {
    if (opcode < 0 || opcode > 255) {
      return null;
    }
    return operations[opcode];
  }

  /**
   * Get the operations array
   *
   * @return the operations array
   */
  public Operation[] getOperations() {
    return operations;
  }

  /**
   * Build method handles for all registered operations. Call this once after all operations are
   * registered.
   *
   * @return this registry for method chaining
   */
  public OperationRegistry buildMethodHandles() {
    operationMethodHandles = new MethodHandle[256];

    for (int i = 0; i < 256; i++) {
      Operation op = operations[i];
      if (op != null) {
        operationMethodHandles[i] = createMethodHandle(op);
      }
    }

    LOG.debug("Built method handles for operation registry");
    return this;
  }

  /**
   * Create a method handle for a specific operation.
   *
   * @param operation the operation
   * @return the method handle bound to the operation instance
   */
  private MethodHandle createMethodHandle(final Operation operation) {
    try {
      Method executeMethod =
          operation.getClass().getMethod("execute", MessageFrame.class, EVM.class);

      MethodHandle handle = lookup.unreflect(executeMethod);
      handle = handle.bindTo(operation);
      handle = handle.asType(EXECUTE_SIGNATURE);

      return handle;

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new IllegalStateException(
          "Failed to create method handle for " + operation.getClass().getSimpleName(), e);
    }
  }

  /**
   * Get the method handles array for all operations.
   *
   * @return array of method handles, indexed by opcode
   */
  public MethodHandle[] getOperationMethodHandles() {
    if (operationMethodHandles == null) {
      throw new IllegalStateException(
          "Method handles have not been built. Call buildMethodHandles() first.");
    }
    return operationMethodHandles;
  }
}

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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptRootResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptStatusResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.api.query.TransactionReceiptWithMetadata;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType;

public class EthGetTransactionReceipt implements JsonRpcMethod {

  private final BlockchainQueries blockchainQueries;

  private final ProtocolSchedule protocolSchedule;

  public EthGetTransactionReceipt(
      final BlockchainQueries blockchainQueries, final ProtocolSchedule protocolSchedule) {
    this.blockchainQueries = blockchainQueries;
    this.protocolSchedule = protocolSchedule;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_GET_TRANSACTION_RECEIPT.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final Hash hash;
    try {
      hash = requestContext.getRequiredParameter(0, Hash.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid transaction hash parameter (index 0)",
          RpcErrorType.INVALID_TRANSACTION_HASH_PARAMS,
          e);
    }
    final TransactionReceiptResult result =
        blockchainQueries
            .transactionReceiptByTransactionHash(hash, protocolSchedule)
            .map(this::getResult)
            .orElse(null);
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), result);
  }

  private TransactionReceiptResult getResult(final TransactionReceiptWithMetadata receipt) {
    if (receipt.getReceipt().getTransactionReceiptType() == TransactionReceiptType.ROOT) {
      return new TransactionReceiptRootResult(receipt);
    } else {
      return new TransactionReceiptStatusResult(receipt);
    }
  }
}

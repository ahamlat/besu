package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.TransactionTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.DebugTraceTransactionResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;


public class DebugTraceTransactionStep  implements Function<TransactionTrace, CompletableFuture<DebugTraceTransactionResult>> {

    @Override
    public CompletableFuture<DebugTraceTransactionResult> apply(final TransactionTrace transactionTrace) {
        return CompletableFuture.completedFuture(new DebugTraceTransactionResult(transactionTrace));
    }
}

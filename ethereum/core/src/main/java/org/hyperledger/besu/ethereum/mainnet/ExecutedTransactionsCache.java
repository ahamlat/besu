package org.hyperledger.besu.ethereum.mainnet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;


public class ExecutedTransactionsCache {

    private final Cache<Hash, TransactionProcessingResult> cache;

    public ExecutedTransactionsCache() {
        this.cache = Caffeine.newBuilder().maximumSize(100).build();
    }
    public void cleanUp() {
        this.cache.cleanUp();
    }

    public TransactionProcessingResult getIfPresent(final Hash codeHash) {
        return cache.getIfPresent(codeHash);
    }

    public void put(final Hash key, final TransactionProcessingResult value) {
        cache.put(key, value);
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }


}

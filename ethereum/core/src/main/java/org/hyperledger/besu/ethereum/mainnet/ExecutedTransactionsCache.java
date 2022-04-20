package org.hyperledger.besu.ethereum.mainnet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;


public class ExecutedTransactionsCache {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutedTransactionsCache.class);

    private final Cache<String, TransactionProcessingResult> cache;

    public ExecutedTransactionsCache() {
        this.cache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
    }
    public void cleanUp() {
        this.cache.cleanUp();
    }

    public TransactionProcessingResult getIfPresent(final String codeHash) {
        LOG.info("getting "+codeHash+" from the cache");
        return cache.getIfPresent(codeHash);
    }

    public void put(final String key, final TransactionProcessingResult value) {
        LOG.info("putting "+key+" in the cache");
        cache.put(key, value);

    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }


}

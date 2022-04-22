package org.hyperledger.besu.ethereum;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

public class ReceiptsCache {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptsCache.class);

    private final Cache<Hash, List<TransactionReceipt>> cache;

    public ReceiptsCache() {
        this.cache = Caffeine.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(5)).build();
    }
    public void cleanUp() {
        this.cache.cleanUp();
    }

    public List<TransactionReceipt> getIfPresent(final Hash codeHash) {
        LOG.info("ReceiptsCache - getting "+codeHash+" from the cache");
        return cache.getIfPresent(codeHash);
    }

    public void put(final Hash key, final List<TransactionReceipt> value) {
        LOG.info("ReceiptsCache - putting "+key+" in the cache");
        cache.put(key, value);

    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

}

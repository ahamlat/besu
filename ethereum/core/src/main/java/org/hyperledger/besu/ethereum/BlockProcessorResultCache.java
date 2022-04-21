package org.hyperledger.besu.ethereum;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ExecutedTransactionsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class BlockProcessorResultCache {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutedTransactionsCache.class);

    private final Cache<Hash, BlockProcessor.Result> cache;

    public BlockProcessorResultCache() {
        this.cache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(Duration.ofMinutes(5)).build();
    }
    public void cleanUp() {
        this.cache.cleanUp();
    }

    public BlockProcessor.Result getIfPresent(final Hash codeHash) {
        LOG.info("BlockProcessorResultCache - getting "+codeHash+" from the cache");
        return cache.getIfPresent(codeHash);
    }

    public void put(final Hash key, final BlockProcessor.Result value) {
        LOG.info("BlockProcessorResultCache - putting "+key+" in the cache");
        cache.put(key, value);

    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

}

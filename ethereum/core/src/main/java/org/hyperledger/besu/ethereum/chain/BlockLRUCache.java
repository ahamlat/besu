package org.hyperledger.besu.ethereum.chain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class BlockLRUCache<Hash, Block> {
    private final Cache<Hash, Block> cache;
    private final Cache<Long, Hash> keyCache;

    public BlockLRUCache(final int maxCapacity) {
        cache = Caffeine.newBuilder()
                .maximumSize(maxCapacity)
                .build();
        keyCache = Caffeine.newBuilder()
                .maximumSize(maxCapacity)
                .build();
    }

    public void put(final Long hashKey, final Hash key, final Block value) {
        keyCache.put(hashKey, key);
        cache.put(key, value);
    }

    public Block get(final Hash key) {
        return cache.getIfPresent(key);
    }

    public Block get(final Long hashKey) {
        Hash key = keyCache.getIfPresent(hashKey);
        if (key != null) return cache.getIfPresent(key);
        else return null;
    }

}
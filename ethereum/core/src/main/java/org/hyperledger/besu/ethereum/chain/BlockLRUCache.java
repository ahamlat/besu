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
        try {
            keyCache.put(hashKey, key);
            cache.put(key, value);
        } catch (Exception ex) {
            System.out.println(ex);
        }
    }

    public Block get(final Hash key) {
        try {
            return cache.getIfPresent(key);
        } catch (Exception ex) {
            System.out.println(ex);
            return null;
        }
    }

    public Block get(final Long hashKey) {
        try {
            Hash key = keyCache.getIfPresent(hashKey);
            if (key != null) return cache.getIfPresent(key);
            else return null;
        } catch (Exception ex) {
            System.out.println(ex);
            return null;
        }

    }

}
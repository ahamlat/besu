package org.hyperledger.besu.ethereum;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;

import java.time.Duration;
import java.util.List;

public class ReceiptsCache {

    private final Cache<Hash, List<TransactionReceipt>> cache;

    public ReceiptsCache() {
        this.cache = Caffeine.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(5)).build();
    }
    public void cleanUp() {
        this.cache.cleanUp();
    }

    public List<TransactionReceipt> getIfPresent(final Hash codeHash) {
        return cache.getIfPresent(codeHash);
    }

    public void put(final Hash key, final List<TransactionReceipt> value) {
        cache.put(key, value);

    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

}

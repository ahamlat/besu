/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.trie.diffbased.bonsai.cache;

import static org.hyperledger.besu.ethereum.trie.CompactEncoding.bytesToPath;
import static org.hyperledger.besu.metrics.BesuMetricCategory.BLOCKCHAIN;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.diffbased.common.StorageSubscriber;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;

public class BonsaiCachedMerkleTrieLoader implements StorageSubscriber {

  private static final int ACCOUNT_CACHE_SIZE = 100_000;
  private static final int STORAGE_CACHE_SIZE = 200_000;
  private final Cache<Bytes, Bytes> accountNodes =
      CacheBuilder.newBuilder().recordStats().maximumSize(ACCOUNT_CACHE_SIZE).build();
  private final Cache<Bytes, Bytes> storageNodes =
      CacheBuilder.newBuilder().recordStats().maximumSize(STORAGE_CACHE_SIZE).build();

  public BonsaiCachedMerkleTrieLoader(final ObservableMetricsSystem metricsSystem) {
    metricsSystem.createGuavaCacheCollector(BLOCKCHAIN, "accountsNodes", accountNodes);
    metricsSystem.createGuavaCacheCollector(BLOCKCHAIN, "storageNodes", storageNodes);
  }

  public void preLoadAccount(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash worldStateRootHash,
      final Address account) {
    CompletableFuture.runAsync(
        () -> cacheAccountNodes(worldStateKeyValueStorage, worldStateRootHash, account));
  }

  @VisibleForTesting
  public void cacheAccountNodes(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash worldStateRootHash,
      final Address account) {
    final long storageSubscriberId = worldStateKeyValueStorage.subscribe(this);
    try {

      Bytes bytesPath = bytesToPath(account.addressHash());
      Optional<SegmentedKeyValueStorage.NearestKeyValue> nearestBefore = worldStateKeyValueStorage.getComposedWorldStateStorage().getNearestBefore(KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE, bytesPath);

      if (nearestBefore.isPresent()) {
        if (nearestBefore.get().wrapBytes().isPresent()) accountNodes.put(nearestBefore.get().key(), nearestBefore.get().wrapBytes().get());
        byte[] path = nearestBefore.get().key().toArrayUnsafe();
        List<byte[]> inputs = new ArrayList<>(path.length);
        for (int i = 1; i < path.length-1; i++) {
          byte[] slice = new byte[i];
          System.arraycopy(path, 0,slice,0,i);
          inputs.add(slice);
        }

        List<byte[]> outputs = worldStateKeyValueStorage.getMultipleKeys(inputs);
        //System.out.println("cacheAccountNodes, outputs.size() : " + outputs.size());
        for (byte[] output : outputs) {
          if (output != null) accountNodes.put(Hash.hash(Bytes.wrap(output)), Bytes.wrap(output));
        }
      }

    } catch (Exception e) {
      System.out.println("Cause : "+ e.getCause() +", message : "+e.getMessage());
      e.printStackTrace();
    } finally {
      worldStateKeyValueStorage.unSubscribe(storageSubscriberId);
    }
  }

  public void preLoadStorageSlot(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Address account,
      final StorageSlotKey slotKey) {
    CompletableFuture.runAsync(
        () -> cacheStorageNodes(worldStateKeyValueStorage, account, slotKey));
  }

  @VisibleForTesting
  public void cacheStorageNodes(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Address account,
      final StorageSlotKey slotKey) {
    final Hash accountHash = account.addressHash();
    final long storageSubscriberId = worldStateKeyValueStorage.subscribe(this);
    try {
      Bytes bytesPath = bytesToPath(slotKey.getSlotHash());
      byte[] accountHashBytes = accountHash.toArrayUnsafe();
      Optional<SegmentedKeyValueStorage.NearestKeyValue> nearestBefore = worldStateKeyValueStorage.getComposedWorldStateStorage().getNearestBefore(KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE, bytesPath);
      if (nearestBefore.isPresent()) {
        byte[] path = nearestBefore.get().key().toArrayUnsafe();
        if (nearestBefore.get().wrapBytes().isPresent()) storageNodes.put(nearestBefore.get().key(), nearestBefore.get().wrapBytes().get());
        int accountHashBytesSize = accountHashBytes.length;
        List<byte[]> inputs = new ArrayList<>(path.length);
        for (int i=1; i < path.length-1; i++)  {
          byte[] slice = new byte[accountHashBytesSize+i];
          System.arraycopy(accountHashBytes, 0, slice, 0, accountHashBytesSize);
          System.arraycopy(path, 0,slice,accountHashBytesSize,i);
          inputs.add(slice);
        }
        List<byte[]> outputs = worldStateKeyValueStorage.getMultipleKeys(inputs);
        for (byte[] output : outputs) {
          if (output != null) storageNodes.put(Hash.hash(Bytes.wrap(output)), Bytes.wrap(output));
        }
      }
    } finally {
      worldStateKeyValueStorage.unSubscribe(storageSubscriberId);
    }
  }

  public Optional<Bytes> getAccountStateTrieNode(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Bytes location,
      final Bytes32 nodeHash) {
    System.out.println("getAccountStateTrieNode, accountNodes.size() : "+accountNodes.size());
    if (nodeHash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
      return Optional.of(MerkleTrie.EMPTY_TRIE_NODE);
    } else {
      return Optional.ofNullable(accountNodes.getIfPresent(nodeHash))
          .or(() -> worldStateKeyValueStorage.getAccountStateTrieNode(location, nodeHash));
    }
  }

  public Optional<Bytes> getAccountStorageTrieNode(
      final BonsaiWorldStateKeyValueStorage worldStateKeyValueStorage,
      final Hash accountHash,
      final Bytes location,
      final Bytes32 nodeHash) {
    if (nodeHash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
      return Optional.of(MerkleTrie.EMPTY_TRIE_NODE);
    } else {
      return Optional.ofNullable(storageNodes.getIfPresent(nodeHash))
          .or(
              () ->
                  worldStateKeyValueStorage.getAccountStorageTrieNode(
                      accountHash, location, nodeHash));
    }
  }
}

/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warms the flat-database read path for the accounts and storage slots a block is about to touch,
 * ahead of (and concurrently with) transaction execution.
 *
 * <p>Reading the working set here moves the expensive part of each SLOAD / account access — RocksDB
 * point lookup, block-cache population, table-reader open, OS page-cache fault — off the EVM's
 * critical path and onto background threads, so execution threads mostly hit warm caches instead of
 * blocking on I/O under the RocksDB cache-shard mutexes.
 *
 * <p>Two sources of prefetch targets are supported:
 *
 * <ul>
 *   <li>a {@link BlockAccessList} (post-Amsterdam), which enumerates every account and slot the
 *       block touches — the complete working set;
 *   <li>the block's transactions (pre-BAL blocks): senders, recipients, the coinbase, and any
 *       EIP-2930/1559/4844 access-list entries. This is a partial working set but covers the
 *       accounts that are guaranteed to be read for every transaction.
 * </ul>
 *
 * <p>Prefetching is strictly read-only and best-effort: tasks read the parent state through the
 * regular {@link BonsaiWorldStateKeyValueStorage} accessors (which also populate the cross-block
 * flat-db cache when it is enabled), and any failure is logged and otherwise ignored.
 */
public final class BlockStatePrefetcher {

  private static final Logger LOG = LoggerFactory.getLogger(BlockStatePrefetcher.class);

  private static final int THREAD_COUNT =
      Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

  private static final ExecutorService PREFETCH_POOL =
      Executors.newFixedThreadPool(
          THREAD_COUNT,
          runnable -> {
            final Thread thread = new Thread(runnable, "block-state-prefetch");
            thread.setDaemon(true);
            return thread;
          });

  private BlockStatePrefetcher() {}

  /**
   * Asynchronously reads every account and storage slot referenced by the block access list. One
   * task is submitted per account so that the slots of an account (which share the same 32-byte key
   * prefix, hence data locality in the storage column family) are read together in a single batched
   * read.
   *
   * @param blockAccessList the block access list of the block about to be processed
   * @param worldStateStorage the storage backing the parent world state
   */
  public static void prefetch(
      final BlockAccessList blockAccessList,
      final BonsaiWorldStateKeyValueStorage worldStateStorage) {
    for (final BlockAccessList.AccountChanges accountChanges : blockAccessList.accountChanges()) {
      PREFETCH_POOL.execute(
          () -> {
            try {
              final Hash accountHash = accountChanges.address().addressHash();
              worldStateStorage.getAccount(accountHash);
              final List<StorageSlotKey> slots =
                  new ArrayList<>(
                      accountChanges.storageReads().size()
                          + accountChanges.storageChanges().size());
              for (final BlockAccessList.SlotRead slotRead : accountChanges.storageReads()) {
                slots.add(slotRead.slot());
              }
              for (final BlockAccessList.SlotChanges slotChanges :
                  accountChanges.storageChanges()) {
                slots.add(slotChanges.slot());
              }
              worldStateStorage.getStorageValuesByStorageSlotKeys(accountHash, slots);
            } catch (final Exception e) {
              LOG.trace("State prefetch failed for {}", accountChanges.address(), e);
            }
          });
    }
  }

  /**
   * Asynchronously reads the state that can be derived from the block's transactions when no block
   * access list is available: the coinbase, each transaction's sender and recipient accounts, and
   * the accounts and slots of EIP-2930 style access lists. As a side effect, resolving {@link
   * Transaction#getSender()} on the prefetch threads also warms the ECDSA sender-recovery cache
   * before execution needs it.
   *
   * @param block the block about to be processed
   * @param worldStateStorage the storage backing the parent world state
   */
  public static void prefetch(
      final Block block, final BonsaiWorldStateKeyValueStorage worldStateStorage) {
    PREFETCH_POOL.execute(
        () -> {
          try {
            worldStateStorage.getAccount(block.getHeader().getCoinbase().addressHash());
          } catch (final Exception e) {
            LOG.trace("State prefetch failed for coinbase", e);
          }
        });
    for (final Transaction transaction : block.getBody().getTransactions()) {
      PREFETCH_POOL.execute(
          () -> {
            try {
              worldStateStorage.getAccount(transaction.getSender().addressHash());
              transaction.getTo().ifPresent(to -> worldStateStorage.getAccount(to.addressHash()));
              transaction
                  .getAccessList()
                  .ifPresent(
                      accessList -> {
                        for (final AccessListEntry entry : accessList) {
                          final Hash accountHash = entry.address().addressHash();
                          worldStateStorage.getAccount(accountHash);
                          final List<StorageSlotKey> slots =
                              entry.storageKeys().stream()
                                  .map(key -> new StorageSlotKey(UInt256.fromBytes(key)))
                                  .toList();
                          worldStateStorage.getStorageValuesByStorageSlotKeys(accountHash, slots);
                        }
                      });
            } catch (final Exception e) {
              LOG.trace("State prefetch failed for transaction {}", transaction.getHash(), e);
            }
          });
    }
  }
}

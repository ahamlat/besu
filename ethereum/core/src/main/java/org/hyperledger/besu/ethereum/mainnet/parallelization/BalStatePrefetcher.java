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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warms the flat-database read path for every account and storage slot referenced by a {@link
 * BlockAccessList}, ahead of (and concurrently with) transaction execution.
 *
 * <p>The block access list tells us, before executing a single transaction, exactly which accounts
 * and slots the block will touch. Reading them here moves the expensive part of each SLOAD /
 * account access — RocksDB point lookup, block-cache population, table-reader open, OS page-cache
 * fault — off the EVM's critical path and onto background threads, so execution threads mostly hit
 * warm caches instead of blocking on I/O under the RocksDB cache-shard mutexes.
 *
 * <p>Prefetching is strictly read-only and best-effort: tasks read the parent state through the
 * regular {@link BonsaiWorldStateKeyValueStorage} accessors (which also populate the cross-block
 * flat-db cache when it is enabled), and any failure is logged and otherwise ignored.
 */
public final class BalStatePrefetcher {

  private static final Logger LOG = LoggerFactory.getLogger(BalStatePrefetcher.class);

  private static final int THREAD_COUNT =
      Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

  private static final ExecutorService PREFETCH_POOL =
      Executors.newFixedThreadPool(
          THREAD_COUNT,
          runnable -> {
            final Thread thread = new Thread(runnable, "bal-state-prefetch");
            thread.setDaemon(true);
            return thread;
          });

  private BalStatePrefetcher() {}

  /**
   * Asynchronously reads every account and storage slot referenced by the block access list. One
   * task is submitted per account so that the slots of an account (which share the same 32-byte key
   * prefix, hence data locality in the storage column family) are read together.
   *
   * @param blockAccessList the block access list of the block about to be processed
   * @param worldStateStorage the storage backing the parent world state
   */
  public static void prefetch(
      final BlockAccessList blockAccessList,
      final BonsaiWorldStateKeyValueStorage worldStateStorage) {
    final long start = System.currentTimeMillis();
    final AtomicInteger remaining = new AtomicInteger(blockAccessList.accountChanges().size());
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
              LOG.trace("BAL state prefetch failed for {}", accountChanges.address(), e);
            } finally {
              if (remaining.decrementAndGet() == 0 && LOG.isDebugEnabled()) {
                LOG.debug(
                    "BAL state prefetch of {} accounts completed in {} ms",
                    blockAccessList.accountChanges().size(),
                    System.currentTimeMillis() - start);
              }
            }
          });
    }
  }
}

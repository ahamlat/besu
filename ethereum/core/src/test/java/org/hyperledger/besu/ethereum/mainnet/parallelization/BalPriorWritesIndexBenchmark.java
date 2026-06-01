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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.AccountChanges;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.BalanceChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Micro-benchmark that isolates the cost previously dominated by {@code
 * applyWritesFromPriorTransactions}: resolving, for every transaction, the latest prior write of
 * each touched account.
 *
 * <p>The synthetic block mirrors the shape of {@code ether-test2.bal.yaml} (≈11.4k transactions,
 * ≈22.9k accounts, dominated by a single "hot" fee/burn account that is written in every
 * transaction). It compares the old eager full-prefix scan (re-scanning every account's whole
 * change list for every transaction — quadratic) against building {@link BalPriorWritesIndex} once
 * and doing per-transaction {@code O(log)} lookups proportional to what each transaction actually
 * reads.
 *
 * <p>Disabled by default; run manually by removing {@link Disabled}. Correctness (state-root
 * parity) is covered by {@code BalParallelBlockProcessorIntegrationTest}; this only measures the
 * algorithmic improvement.
 */
@Disabled("performance benchmark; run manually")
class BalPriorWritesIndexBenchmark {

  private static final int TX_COUNT = 11_424;
  private static final int ACCOUNT_COUNT = 22_861;
  private static final Address HOT_ACCOUNT = Address.ZERO; // fee/burn accumulator

  @Test
  void eagerVsLazyPriorWritesResolution() {
    final List<AccountChanges> accountChanges = new ArrayList<>(ACCOUNT_COUNT);

    // Hot account: a balance change in every transaction (the quadratic amplifier).
    final List<BalanceChange> hotBalances = new ArrayList<>(TX_COUNT);
    for (int tx = 0; tx < TX_COUNT; tx++) {
      hotBalances.add(new BalanceChange(tx, Wei.of(1_000_000L + tx)));
    }
    accountChanges.add(
        new AccountChanges(HOT_ACCOUNT, List.of(), List.of(), hotBalances, List.of(), List.of()));

    // Normal accounts: one balance change each at a pseudo-random transaction index. The
    // per-transaction "footprint" (sender + recipient) is recorded so the lazy path can resolve
    // only what each transaction touches.
    final Random random = new Random(42);
    final List<List<Address>> touchedByTx = new ArrayList<>(TX_COUNT);
    for (int tx = 0; tx < TX_COUNT; tx++) {
      final List<Address> touched = new ArrayList<>(3);
      touched.add(HOT_ACCOUNT);
      touchedByTx.add(touched);
    }
    for (int a = 1; a < ACCOUNT_COUNT; a++) {
      final Address address = Address.wrap(Bytes.random(20, random));
      final int tx = random.nextInt(TX_COUNT);
      accountChanges.add(
          new AccountChanges(
              address,
              List.of(),
              List.of(),
              List.of(new BalanceChange(tx, Wei.of(100L + a))),
              List.of(),
              List.of()));
      touchedByTx.get(tx).add(address);
    }

    final BlockAccessList bal = new BlockAccessList(accountChanges);

    // ---------- Eager: re-scan every account's full change list for every transaction ----------
    long eagerStart = System.nanoTime();
    long eagerChecksum = 0L;
    for (int tx = 0; tx < TX_COUNT; tx++) {
      final long balIndex = tx + 1L;
      for (final AccountChanges ac : bal.accountChanges()) {
        BalanceChange latest = null;
        long latestIndex = -1L;
        for (final BalanceChange bc : ac.balanceChanges()) {
          if (bc.txIndex() < balIndex && bc.txIndex() > latestIndex) {
            latest = bc;
            latestIndex = bc.txIndex();
          }
        }
        if (latest != null) {
          eagerChecksum += latest.postBalance().toLong();
        }
      }
    }
    final long eagerNanos = System.nanoTime() - eagerStart;

    // ---------- Lazy: build the index once, then resolve only each tx's footprint ----------
    final long buildStart = System.nanoTime();
    final BalPriorWritesIndex index = new BalPriorWritesIndex(bal);
    final long buildNanos = System.nanoTime() - buildStart;

    final long lazyStart = System.nanoTime();
    long lazyChecksum = 0L;
    for (int tx = 0; tx < TX_COUNT; tx++) {
      final long balIndex = tx + 1L;
      for (final Address address : touchedByTx.get(tx)) {
        final Wei balance = index.latestBalanceBefore(address, balIndex);
        if (balance != null) {
          lazyChecksum += balance.toLong();
        }
      }
    }
    final long lazyNanos = System.nanoTime() - lazyStart;

    System.out.printf(
        "%n=== Prior-writes resolution benchmark (txs=%d, accounts=%d) ===%n",
        TX_COUNT, ACCOUNT_COUNT);
    System.out.printf("eager full-prefix scan : %8.1f ms%n", eagerNanos / 1_000_000.0);
    System.out.printf("index build (once)     : %8.1f ms%n", buildNanos / 1_000_000.0);
    System.out.printf("lazy footprint lookups : %8.1f ms%n", lazyNanos / 1_000_000.0);
    System.out.printf(
        "speedup (eager / (build+lazy)) : %.1fx%n", (double) eagerNanos / (buildNanos + lazyNanos));
    System.out.printf("(checksums eager=%d lazy=%d)%n", eagerChecksum, lazyChecksum);

    // The lazy footprint resolution is dramatically cheaper than the eager full-prefix scan.
    assertThat(buildNanos + lazyNanos).isLessThan(eagerNanos);
  }
}

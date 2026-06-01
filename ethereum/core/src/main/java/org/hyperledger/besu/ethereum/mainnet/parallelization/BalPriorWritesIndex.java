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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Pre-computed, read-only index over a {@link BlockAccessList}.
 *
 * <p>For any boundary {@code balIndex} (the current transaction index + 1) it answers, in {@code
 * O(log C)}, the effective prior write (balance / nonce / code / storage slot) that a touched
 * account had at the end of transaction {@code balIndex - 1}. This lets the parallel BAL processor
 * seed prior state lazily, on the read path, instead of eagerly replaying the whole {@code [0,
 * balIndex)} prefix for every transaction.
 *
 * <p>Construction is {@code O(total change events)} and happens once per block, off the parallel
 * critical path. The resulting structure is immutable and therefore safe to share across the
 * per-transaction worker threads.
 */
public final class BalPriorWritesIndex {

  /** Per-account, immutable, pre-sorted timelines split by change kind. */
  private static final class AccountEntry {
    final long[] balanceTx;
    final Wei[] balanceVal;
    final long[] nonceTx;
    final long[] nonceVal;
    final long[] codeTx;
    final Bytes[] codeVal;
    final Map<StorageSlotKey, SlotEntry> storage;

    AccountEntry(
        final long[] balanceTx,
        final Wei[] balanceVal,
        final long[] nonceTx,
        final long[] nonceVal,
        final long[] codeTx,
        final Bytes[] codeVal,
        final Map<StorageSlotKey, SlotEntry> storage) {
      this.balanceTx = balanceTx;
      this.balanceVal = balanceVal;
      this.nonceTx = nonceTx;
      this.nonceVal = nonceVal;
      this.codeTx = codeTx;
      this.codeVal = codeVal;
      this.storage = storage;
    }
  }

  /** Per-slot, immutable, pre-sorted change timeline for a single account. */
  private static final class SlotEntry {
    final long[] tx;
    final UInt256[] val;

    SlotEntry(final long[] tx, final UInt256[] val) {
      this.tx = tx;
      this.val = val;
    }
  }

  private final Map<Address, AccountEntry> byAddress;

  /**
   * Builds the index by iterating over every touched account exactly once.
   *
   * @param bal the block access list for the current block
   */
  public BalPriorWritesIndex(final BlockAccessList bal) {
    final Map<Address, AccountEntry> built = new HashMap<>();
    for (final BlockAccessList.AccountChanges ac : bal.accountChanges()) {
      final AccountEntry entry = buildEntry(ac);
      if (entry != null) {
        built.put(ac.address(), entry);
      }
    }
    this.byAddress = built;
  }

  private static AccountEntry buildEntry(final BlockAccessList.AccountChanges ac) {
    final int balanceCount = ac.balanceChanges().size();
    final int nonceCount = ac.nonceChanges().size();
    final int codeCount = ac.codeChanges().size();
    final int slotCount = ac.storageChanges().size();
    if (balanceCount == 0 && nonceCount == 0 && codeCount == 0 && slotCount == 0) {
      return null;
    }

    final long[] balanceTx = new long[balanceCount];
    final Wei[] balanceVal = new Wei[balanceCount];
    int i = 0;
    for (final BlockAccessList.BalanceChange bc : ac.balanceChanges()) {
      balanceTx[i] = bc.txIndex();
      balanceVal[i] = bc.postBalance();
      i++;
    }
    sortByTx(balanceTx, balanceVal);

    final long[] nonceTx = new long[nonceCount];
    final long[] nonceVal = new long[nonceCount];
    i = 0;
    for (final BlockAccessList.NonceChange nc : ac.nonceChanges()) {
      nonceTx[i] = nc.txIndex();
      nonceVal[i] = nc.newNonce();
      i++;
    }
    sortByTx(nonceTx, nonceVal);

    final long[] codeTx = new long[codeCount];
    final Bytes[] codeVal = new Bytes[codeCount];
    i = 0;
    for (final BlockAccessList.CodeChange cc : ac.codeChanges()) {
      codeTx[i] = cc.txIndex();
      codeVal[i] = cc.newCode();
      i++;
    }
    sortByTx(codeTx, codeVal);

    final Map<StorageSlotKey, SlotEntry> storage;
    if (slotCount == 0) {
      storage = Map.of();
    } else {
      storage = new HashMap<>(slotCount);
      for (final BlockAccessList.SlotChanges sc : ac.storageChanges()) {
        final int n = sc.changes().size();
        final long[] tx = new long[n];
        final UInt256[] val = new UInt256[n];
        int j = 0;
        for (final BlockAccessList.StorageChange ch : sc.changes()) {
          tx[j] = ch.txIndex();
          val[j] = ch.newValue() != null ? ch.newValue() : UInt256.ZERO;
          j++;
        }
        sortByTx(tx, val);
        storage.put(sc.slot(), new SlotEntry(tx, val));
      }
    }

    return new AccountEntry(balanceTx, balanceVal, nonceTx, nonceVal, codeTx, codeVal, storage);
  }

  /**
   * Sorts the parallel {@code (tx, val)} arrays in place, ascending by {@code tx}. No-op when the
   * input is already ascending (the common case for the canonical EIP-7928 encoding).
   */
  private static void sortByTx(final long[] tx, final Object[] val) {
    if (isAscending(tx)) {
      return;
    }
    final Integer[] order = sortedOrder(tx);
    final long[] txCopy = tx.clone();
    final Object[] valCopy = val.clone();
    for (int i = 0; i < order.length; i++) {
      tx[i] = txCopy[order[i]];
      val[i] = valCopy[order[i]];
    }
  }

  /** Primitive-valued variant of {@link #sortByTx(long[], Object[])} (used for nonces). */
  private static void sortByTx(final long[] tx, final long[] val) {
    if (isAscending(tx)) {
      return;
    }
    final Integer[] order = sortedOrder(tx);
    final long[] txCopy = tx.clone();
    final long[] valCopy = val.clone();
    for (int i = 0; i < order.length; i++) {
      tx[i] = txCopy[order[i]];
      val[i] = valCopy[order[i]];
    }
  }

  private static boolean isAscending(final long[] tx) {
    for (int i = 1; i < tx.length; i++) {
      if (tx[i] < tx[i - 1]) {
        return false;
      }
    }
    return true;
  }

  private static Integer[] sortedOrder(final long[] tx) {
    final Integer[] order = new Integer[tx.length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Arrays.sort(order, Comparator.comparingLong(a -> tx[a]));
    return order;
  }

  /**
   * Returns the balance an account had at the end of transaction {@code balIndex - 1}, or {@code
   * null} if no prior transaction changed its balance.
   *
   * @param address the account
   * @param balIndex transaction-index boundary (current tx index + 1)
   * @return the latest prior balance, or {@code null}
   */
  public Wei latestBalanceBefore(final Address address, final long balIndex) {
    final AccountEntry e = byAddress.get(address);
    if (e == null) {
      return null;
    }
    final int idx = upperBound(e.balanceTx, balIndex) - 1;
    return idx >= 0 ? e.balanceVal[idx] : null;
  }

  /**
   * Returns the nonce an account had at the end of transaction {@code balIndex - 1}, or {@code
   * null} if no prior transaction changed its nonce.
   *
   * @param address the account
   * @param balIndex transaction-index boundary (current tx index + 1)
   * @return the latest prior nonce (boxed), or {@code null}
   */
  public Long latestNonceBefore(final Address address, final long balIndex) {
    final AccountEntry e = byAddress.get(address);
    if (e == null) {
      return null;
    }
    final int idx = upperBound(e.nonceTx, balIndex) - 1;
    return idx >= 0 ? e.nonceVal[idx] : null;
  }

  /**
   * Returns the code an account had at the end of transaction {@code balIndex - 1}, or {@code null}
   * if no prior transaction changed its code.
   *
   * @param address the account
   * @param balIndex transaction-index boundary (current tx index + 1)
   * @return the latest prior code, or {@code null}
   */
  public Bytes latestCodeBefore(final Address address, final long balIndex) {
    final AccountEntry e = byAddress.get(address);
    if (e == null) {
      return null;
    }
    final int idx = upperBound(e.codeTx, balIndex) - 1;
    return idx >= 0 ? e.codeVal[idx] : null;
  }

  /**
   * Returns the value a storage slot had at the end of transaction {@code balIndex - 1}, or {@code
   * null} if no prior transaction wrote that slot.
   *
   * @param address the account
   * @param slotKey the storage slot
   * @param balIndex transaction-index boundary (current tx index + 1)
   * @return the latest prior slot value, or {@code null}
   */
  public UInt256 latestStorageBefore(
      final Address address, final StorageSlotKey slotKey, final long balIndex) {
    final AccountEntry e = byAddress.get(address);
    if (e == null || e.storage.isEmpty()) {
      return null;
    }
    final SlotEntry slot = e.storage.get(slotKey);
    if (slot == null) {
      return null;
    }
    final int idx = upperBound(slot.tx, balIndex) - 1;
    return idx >= 0 ? slot.val[idx] : null;
  }

  /**
   * Returns {@code true} if the index has no relevant prior write for the account (used to skip
   * seeding entirely on the read path).
   *
   * @param address the account
   * @return {@code true} when the account has no recorded change
   */
  public boolean hasNoChanges(final Address address) {
    return !byAddress.containsKey(address);
  }

  /**
   * Number of touched-account timelines in this index (diagnostics / tests).
   *
   * @return number of indexed accounts
   */
  int size() {
    return byAddress.size();
  }

  /**
   * Index of the first element whose {@code txIndex >= balIndex}. {@code [0, result)} is the prefix
   * of events strictly before {@code balIndex}; per EIP-7928 the input lists are already sorted
   * ascending by {@code txIndex}.
   */
  private static int upperBound(final long[] txs, final long balIndex) {
    int lo = 0;
    int hi = txs.length;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (txs[mid] < balIndex) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}

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
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.AccountChanges;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.BalanceChange;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.CodeChange;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.NonceChange;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.SlotChanges;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.StorageChange;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class BalPriorWritesIndexTest {

  private static final Address ADDRESS =
      Address.fromHexString("0x1000000000000000000000000000000000000001");
  private static final Address UNKNOWN =
      Address.fromHexString("0x2000000000000000000000000000000000000002");
  private static final StorageSlotKey SLOT = new StorageSlotKey(UInt256.valueOf(7));
  private static final StorageSlotKey OTHER_SLOT = new StorageSlotKey(UInt256.valueOf(8));

  private static BlockAccessList balWith(final AccountChanges... changes) {
    return new BlockAccessList(List.of(changes));
  }

  private static AccountChanges account(
      final List<BalanceChange> balances,
      final List<NonceChange> nonces,
      final List<CodeChange> codes,
      final List<SlotChanges> storage) {
    return new AccountChanges(ADDRESS, storage, List.of(), balances, nonces, codes);
  }

  @Test
  void returnsNullForUntouchedAccount() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(new BalanceChange(1, Wei.of(100))), List.of(), List.of(), List.of())));

    assertThat(index.latestBalanceBefore(UNKNOWN, 10)).isNull();
    assertThat(index.latestNonceBefore(UNKNOWN, 10)).isNull();
    assertThat(index.latestCodeBefore(UNKNOWN, 10)).isNull();
    assertThat(index.latestStorageBefore(UNKNOWN, SLOT, 10)).isNull();
    assertThat(index.hasNoChanges(UNKNOWN)).isTrue();
    assertThat(index.hasNoChanges(ADDRESS)).isFalse();
  }

  @Test
  void returnsLatestBalanceStrictlyBeforeBoundary() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(
                        new BalanceChange(1, Wei.of(100)),
                        new BalanceChange(3, Wei.of(300)),
                        new BalanceChange(7, Wei.of(700))),
                    List.of(),
                    List.of(),
                    List.of())));

    // Nothing strictly before tx 1.
    assertThat(index.latestBalanceBefore(ADDRESS, 1)).isNull();
    // Exact boundary: balIndex 3 must not see the write at tx 3.
    assertThat(index.latestBalanceBefore(ADDRESS, 3)).isEqualTo(Wei.of(100));
    assertThat(index.latestBalanceBefore(ADDRESS, 4)).isEqualTo(Wei.of(300));
    // Beyond the last write returns the last value.
    assertThat(index.latestBalanceBefore(ADDRESS, 100)).isEqualTo(Wei.of(700));
  }

  @Test
  void returnsLatestNonceAndCode() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(),
                    List.of(new NonceChange(2, 5L), new NonceChange(6, 9L)),
                    List.of(
                        new CodeChange(2, Bytes.fromHexString("0xaa")),
                        new CodeChange(6, Bytes.fromHexString("0xbb"))),
                    List.of())));

    assertThat(index.latestNonceBefore(ADDRESS, 2)).isNull();
    assertThat(index.latestNonceBefore(ADDRESS, 3)).isEqualTo(5L);
    assertThat(index.latestNonceBefore(ADDRESS, 7)).isEqualTo(9L);

    assertThat(index.latestCodeBefore(ADDRESS, 2)).isNull();
    assertThat(index.latestCodeBefore(ADDRESS, 3)).isEqualTo(Bytes.fromHexString("0xaa"));
    assertThat(index.latestCodeBefore(ADDRESS, 7)).isEqualTo(Bytes.fromHexString("0xbb"));
  }

  @Test
  void returnsLatestStoragePerSlotAndZeroForNullWrite() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                        new SlotChanges(
                            SLOT,
                            List.of(
                                new StorageChange(1, UInt256.valueOf(11)),
                                new StorageChange(4, null))),
                        new SlotChanges(
                            OTHER_SLOT, List.of(new StorageChange(2, UInt256.valueOf(22))))))));

    assertThat(index.latestStorageBefore(ADDRESS, SLOT, 1)).isNull();
    assertThat(index.latestStorageBefore(ADDRESS, SLOT, 2)).isEqualTo(UInt256.valueOf(11));
    // A null newValue is treated as a zeroing write.
    assertThat(index.latestStorageBefore(ADDRESS, SLOT, 5)).isEqualTo(UInt256.ZERO);
    // Distinct slots are tracked independently.
    assertThat(index.latestStorageBefore(ADDRESS, OTHER_SLOT, 5)).isEqualTo(UInt256.valueOf(22));
    // An unwritten slot has no prior value.
    assertThat(index.latestStorageBefore(ADDRESS, new StorageSlotKey(UInt256.valueOf(99)), 5))
        .isNull();
  }

  @Test
  void toleratesUnsortedInputOrder() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(
                        new BalanceChange(7, Wei.of(700)),
                        new BalanceChange(1, Wei.of(100)),
                        new BalanceChange(3, Wei.of(300))),
                    List.of(),
                    List.of(),
                    List.of())));

    assertThat(index.latestBalanceBefore(ADDRESS, 4)).isEqualTo(Wei.of(300));
    assertThat(index.latestBalanceBefore(ADDRESS, 2)).isEqualTo(Wei.of(100));
    assertThat(index.latestBalanceBefore(ADDRESS, 100)).isEqualTo(Wei.of(700));
  }

  @Test
  void indexesOnlyAccountsWithChanges() {
    final BalPriorWritesIndex index =
        new BalPriorWritesIndex(
            balWith(
                account(
                    List.of(new BalanceChange(1, Wei.of(1))), List.of(), List.of(), List.of())));

    assertThat(index.size()).isEqualTo(1);
  }
}

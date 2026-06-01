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
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiAccount;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.common.PathBasedValue;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.PathBasedWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.PathBasedWorldView;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.PathBasedWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.preload.Consumer;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.preload.StorageConsumingMap;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * A Bonsai update accumulator that seeds the effect of prior transactions in the block lazily, on
 * the read path, using a {@link BalPriorWritesIndex}.
 *
 * <p>Instead of eagerly replaying every prior write of the {@code [0, balIndex)} prefix before each
 * transaction (which is quadratic in the number of transactions), this accumulator resolves the
 * value of an account or storage slot to its state at the end of transaction {@code balIndex - 1}
 * the first time the EVM actually reads it. Work is therefore proportional to what each transaction
 * touches, and correctness holds for any kind of read (balance / nonce / code / storage) because we
 * intercept the read itself rather than guessing a footprint.
 *
 * <p>Seeded entries keep their {@code prior} value equal to the block-start (parent) value and only
 * their {@code updated} value reflects the as-of-{@code (balIndex - 1)} state — exactly the shape
 * the eager path produced — so downstream block-state assembly (importStateChangesFromSource)
 * remains correct.
 */
public class BalLazyPriorWritesWorldStateUpdateAccumulator
    extends BonsaiWorldStateUpdateAccumulator {

  private final BalPriorWritesIndex priorWritesIndex;
  private final long balIndex;

  /**
   * @param world the parent (block-start) world view
   * @param accountPreloader account preloader
   * @param storagePreloader storage slot preloader
   * @param evmConfiguration evm configuration
   * @param codeCache code cache
   * @param priorWritesIndex the per-block prior-writes index
   * @param balIndex transaction-index boundary for this worker (current tx index + 1)
   */
  public BalLazyPriorWritesWorldStateUpdateAccumulator(
      final PathBasedWorldView world,
      final Consumer<PathBasedValue<BonsaiAccount>> accountPreloader,
      final Consumer<StorageSlotKey> storagePreloader,
      final EvmConfiguration evmConfiguration,
      final CodeCache codeCache,
      final BalPriorWritesIndex priorWritesIndex,
      final long balIndex) {
    super(world, accountPreloader, storagePreloader, evmConfiguration, codeCache);
    this.priorWritesIndex = priorWritesIndex;
    this.balIndex = balIndex;
  }

  @Override
  protected BonsaiAccount loadAccount(
      final Address address,
      final Function<PathBasedValue<BonsaiAccount>, BonsaiAccount> accountFunction) {
    final boolean firstLoad = !getAccountsToUpdate().containsKey(address);
    final BonsaiAccount loaded = super.loadAccount(address, accountFunction);
    if (!firstLoad || priorWritesIndex.hasNoChanges(address)) {
      return loaded;
    }

    final Wei priorBalance = priorWritesIndex.latestBalanceBefore(address, balIndex);
    final Long priorNonce = priorWritesIndex.latestNonceBefore(address, balIndex);
    final Bytes priorCode = priorWritesIndex.latestCodeBefore(address, balIndex);
    if (priorBalance == null && priorNonce == null && priorCode == null) {
      return loaded;
    }

    final PathBasedValue<BonsaiAccount> value = getAccountsToUpdate().get(address);
    BonsaiAccount updated = value == null ? null : value.getUpdated();

    if (updated == null) {
      // The account did not exist at the start of the block but was created by a prior
      // transaction; synthesize it from the recorded prior writes.
      final long nonce = priorNonce != null ? priorNonce : 0L;
      final Wei balance = priorBalance != null ? priorBalance : Wei.ZERO;
      updated =
          createAccount(
              this,
              address,
              hashAndSaveAccountPreImage(address),
              nonce,
              balance,
              Hash.EMPTY_TRIE_HASH,
              Hash.EMPTY,
              true);
      if (value == null) {
        getAccountsToUpdate().put(address, new PathBasedValue<>(null, updated));
      } else {
        value.setUpdated(updated);
      }
    } else {
      if (priorBalance != null) {
        updated.setBalance(priorBalance);
      }
      if (priorNonce != null) {
        updated.setNonce(priorNonce);
      }
    }

    if (priorCode != null) {
      updated.setCode(priorCode);
      seedCode(address, priorCode, value);
    }

    final PathBasedValue<BonsaiAccount> seeded = getAccountsToUpdate().get(address);
    return seeded == null ? null : accountFunction.apply(seeded);
  }

  private void seedCode(
      final Address address, final Bytes code, final PathBasedValue<BonsaiAccount> accountValue) {
    final Hash priorCodeHash =
        accountValue != null && accountValue.getPrior() != null
            ? accountValue.getPrior().getCodeHash()
            : Hash.EMPTY;
    final Bytes existingCode = wrappedWorldView().getCode(address, priorCodeHash).orElse(null);
    getCodeToUpdate().put(address, new PathBasedValue<>(existingCode, code));
  }

  @Override
  public Optional<UInt256> getStorageValueByStorageSlotKey(
      final Address address, final StorageSlotKey storageSlotKey) {
    final Map<StorageSlotKey, PathBasedValue<UInt256>> localAccountStorage =
        getStorageToUpdate().get(address);
    if (localAccountStorage != null) {
      final PathBasedValue<UInt256> value = localAccountStorage.get(storageSlotKey);
      if (value != null) {
        return Optional.ofNullable(value.getUpdated());
      }
    }

    final UInt256 priorWrite =
        priorWritesIndex.latestStorageBefore(address, storageSlotKey, balIndex);
    if (priorWrite == null) {
      return super.getStorageValueByStorageSlotKey(address, storageSlotKey);
    }

    final Optional<UInt256> parentValue =
        (wrappedWorldView() instanceof PathBasedWorldState worldState)
            ? worldState.getStorageValueByStorageSlotKey(address, storageSlotKey)
            : wrappedWorldView().getStorageValueByStorageSlotKey(address, storageSlotKey);
    getStorageToUpdate()
        .computeIfAbsent(
            address,
            key ->
                new StorageConsumingMap<>(
                    address, new ConcurrentHashMap<>(), getStoragePreloader()))
        .put(storageSlotKey, new PathBasedValue<>(parentValue.orElse(null), priorWrite));
    return Optional.of(priorWrite);
  }

  @Override
  public PathBasedWorldStateUpdateAccumulator<BonsaiAccount> copy() {
    final BalLazyPriorWritesWorldStateUpdateAccumulator copy =
        new BalLazyPriorWritesWorldStateUpdateAccumulator(
            wrappedWorldView(),
            getAccountPreloader(),
            getStoragePreloader(),
            getEvmConfiguration(),
            codeCache(),
            priorWritesIndex,
            balIndex);
    copy.cloneFromUpdater(this);
    return copy;
  }
}

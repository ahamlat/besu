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
package org.hyperledger.besu.evm.worldstate;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * The Stacked updater.
 *
 * @param <W> the WorldView type parameter
 * @param <A> the Account type parameter
 */
public class StackedUpdater<W extends WorldView, A extends Account>
    extends AbstractWorldUpdater<AbstractWorldUpdater<W, A>, UpdateTrackingAccount<A>> {

  /**
   * Instantiates a new Stacked updater.
   *
   * @param world the world
   * @param evmConfiguration the EVM Configuration parameters
   */
  public StackedUpdater(
      final AbstractWorldUpdater<W, A> world, final EvmConfiguration evmConfiguration) {
    super(world, evmConfiguration, null, null);
  }

  @Override
  public Account get(final Address address) {
    if (updatedAccounts != null && !updatedAccounts.isEmpty()) {
      final Account existing = updatedAccounts.get(address);
      if (existing != null) {
        return existing;
      }
    }
    if (hasDeletedAccounts && deletedAccounts != null && deletedAccounts.contains(address)) {
      return null;
    }
    // Read-only path: delegate directly to wrapped WorldUpdater.get(address) without wrapping
    // unmodified accounts in UpdateTrackingAccount!
    return wrappedWorldView().get(address);
  }

  @Override
  protected UpdateTrackingAccount<A> getForMutation(final Address address) {
    final AbstractWorldUpdater<W, A> wrapped = wrappedWorldView();
    if (wrapped.updatedAccounts != null && !wrapped.updatedAccounts.isEmpty()) {
      final UpdateTrackingAccount<A> wrappedTracker = wrapped.updatedAccounts.get(address);
      if (wrappedTracker != null) {
        return wrappedTracker;
      }
    }
    if (wrapped.hasDeletedAccounts
        && wrapped.deletedAccounts != null
        && wrapped.deletedAccounts.contains(address)) {
      return null;
    }
    // The wrapped one isn't tracking that account. We're creating a tracking "for him" (but
    // don't add him yet to his tracking map) because we need it to satisfy the type system.
    // We will recognize this case in commit below and use that tracker "pay back" our
    // allocation, so this isn't lost.
    final A account = wrappedWorldView().getForMutation(address);
    return account == null ? null : new UpdateTrackingAccount<>(account);
  }

  @SuppressWarnings("MixedMutabilityReturnType")
  @Override
  public Collection<? extends Account> getTouchedAccounts() {
    if (updatedAccounts == null || updatedAccounts.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(updatedAccounts.values());
  }

  @SuppressWarnings("MixedMutabilityReturnType")
  @Override
  public Collection<Address> getDeletedAccountAddresses() {
    if (!hasDeletedAccounts || deletedAccounts == null || deletedAccounts.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(deletedAccounts);
  }

  @Override
  public void revert() {
    reset();
  }

  @Override
  public void commit() {
    final boolean hasUpdates = updatedAccounts != null && !updatedAccounts.isEmpty();
    final boolean hasDeletes =
        hasDeletedAccounts && deletedAccounts != null && !deletedAccounts.isEmpty();
    if (!hasUpdates && !hasDeletes) {
      return;
    }
    final AbstractWorldUpdater<W, A> wrapped = wrappedWorldView();
    if (hasDeletes) {
      if (wrapped.updatedAccounts != null) {
        for (final Address deleted : deletedAccounts) {
          wrapped.updatedAccounts.remove(deleted);
        }
      }
      wrapped.ensureDeletedAccounts().addAll(deletedAccounts);
      wrapped.hasDeletedAccounts = true;
    }
    if (hasUpdates) {
      final var wrappedUpdated = wrapped.ensureUpdatedAccounts();
      for (final UpdateTrackingAccount<UpdateTrackingAccount<A>> update :
          updatedAccounts.values()) {
        final Address addr = update.getAddress();
        if (wrapped.hasDeletedAccounts && wrapped.deletedAccounts != null) {
          wrapped.deletedAccounts.remove(addr);
        }
        UpdateTrackingAccount<A> existing = wrappedUpdated.get(addr);
        if (existing == null) {
          existing = update.getWrappedAccount();
          if (existing == null) {
            existing = new UpdateTrackingAccount<>(addr);
          }
          wrappedUpdated.put(addr, existing);
        }
        existing.setNonce(update.getNonce());
        existing.setBalance(update.getBalance());
        if (update.codeWasUpdated()) {
          existing.setCode(update.getCode());
        }
        if (update.getStorageWasCleared()) {
          existing.clearStorage();
        }
        final var storage = update.getUpdatedStorage();
        if (!storage.isEmpty()) {
          storage.forEach(existing::setStorageValue);
        }
      }
    }
  }

  /** Mark transaction boundary. */
  @Override
  public void markTransactionBoundary() {
    getUpdatedAccounts().forEach(UpdateTrackingAccount::markTransactionBoundary);
  }
}

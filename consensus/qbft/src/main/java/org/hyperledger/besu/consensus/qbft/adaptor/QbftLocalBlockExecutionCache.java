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
package org.hyperledger.besu.consensus.qbft.adaptor;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the world state and receipts from local QBFT block creation so import can persist them
 * without a second EVM execution.
 */
public class QbftLocalBlockExecutionCache {

  private static final Logger LOG = LoggerFactory.getLogger(QbftLocalBlockExecutionCache.class);

  private final AtomicReference<CachedExecution> pending = new AtomicReference<>();

  /**
   * Cached result of a locally created block.
   *
   * @param unsealedBlockHash hash of the unsealed proposed block
   * @param worldState world state after transaction selection
   * @param receipts receipts produced during transaction selection
   */
  public record CachedExecution(
      Hash unsealedBlockHash, MutableWorldState worldState, List<TransactionReceipt> receipts) {}

  /**
   * Store a new local execution result. Closes any previous retained world state.
   *
   * @param execution the cached execution
   */
  public void store(final CachedExecution execution) {
    closeQuietly(pending.getAndSet(execution));
  }

  /**
   * Take the cached execution for the given unsealed block hash.
   *
   * @param unsealedBlockHash hash of the unsealed proposed block
   * @return the cached execution if it matches
   */
  public Optional<CachedExecution> take(final Hash unsealedBlockHash) {
    final CachedExecution current = pending.get();
    if (current != null && current.unsealedBlockHash().equals(unsealedBlockHash)) {
      pending.compareAndSet(current, null);
      return Optional.of(current);
    }
    return Optional.empty();
  }

  /** Discard any retained execution without importing it. */
  public void discard() {
    closeQuietly(pending.getAndSet(null));
  }

  private void closeQuietly(final CachedExecution execution) {
    if (execution == null) {
      return;
    }
    try {
      execution.worldState().close();
    } catch (final Exception e) {
      LOG.debug("Failed to close retained QBFT world state", e);
    }
  }
}

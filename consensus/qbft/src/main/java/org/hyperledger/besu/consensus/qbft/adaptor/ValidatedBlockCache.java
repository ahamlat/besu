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
package org.hyperledger.besu.consensus.qbft.adaptor;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the outputs of a successful proposal validation so that importing the committed block does
 * not execute its transactions a second time.
 *
 * <p>Validating a proposal processes the block on a frozen copy of the parent world state, which
 * saves the block's trie log. Once the round commits, the importer only needs the receipts to
 * append the block and can move the head world state forward with that trie log.
 */
public class ValidatedBlockCache {

  /** Proposals validated at the current height, plus a few left over from round changes. */
  private static final int MAX_ENTRIES = 4;

  /**
   * The outputs of a validated block that the import needs.
   *
   * @param blockNumber the number of the validated block
   * @param receipts the receipts produced by processing the block
   * @param blockAccessList the block access list produced by processing the block, if any
   */
  public record ValidatedBlock(
      long blockNumber,
      List<TransactionReceipt> receipts,
      Optional<BlockAccessList> blockAccessList) {}

  private final Map<Hash, ValidatedBlock> entries =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Hash, ValidatedBlock> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  /** Creates an empty cache. */
  public ValidatedBlockCache() {}

  /**
   * Records the outputs of a block that passed validation.
   *
   * @param blockHash the hash of the validated block
   * @param validatedBlock the outputs of the validation
   */
  public synchronized void put(final Hash blockHash, final ValidatedBlock validatedBlock) {
    entries.put(blockHash, validatedBlock);
  }

  /**
   * Removes and returns the outputs recorded for a block, dropping any entry at or below its
   * height, as no other block can be imported at those heights afterwards.
   *
   * @param blockHash the hash of the block being imported
   * @param blockNumber the number of the block being imported
   * @return the outputs recorded for the block, if it was validated
   */
  public synchronized Optional<ValidatedBlock> take(final Hash blockHash, final long blockNumber) {
    final ValidatedBlock validatedBlock = entries.remove(blockHash);
    entries.values().removeIf(entry -> entry.blockNumber() <= blockNumber);
    return Optional.ofNullable(validatedBlock);
  }
}

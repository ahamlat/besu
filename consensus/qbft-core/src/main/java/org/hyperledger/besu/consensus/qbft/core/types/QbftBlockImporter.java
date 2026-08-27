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
package org.hyperledger.besu.consensus.qbft.core.types;

import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;

import java.util.List;
import java.util.Optional;

/** Imports a block into the chain. */
public interface QbftBlockImporter {

  /**
   * Import a block into the chain.
   *
   * @param block to import
   * @param blockAccessList block access list
   * @return true if the block was successfully imported, false otherwise
   */
  boolean importBlock(QbftBlock block, Optional<BlockAccessList> blockAccessList);

  /**
   * Import a block that this node created locally. Implementations may skip re-execution when the
   * world state from block creation is still available.
   *
   * @param sealedBlock the sealed block to import
   * @param proposedBlock the unsealed proposed block
   * @param blockAccessList block access list
   * @param receipts receipts from local block creation
   * @return true if the block was successfully imported, false otherwise
   */
  default boolean importLocallyCreatedBlock(
      final QbftBlock sealedBlock,
      final QbftBlock proposedBlock,
      final Optional<BlockAccessList> blockAccessList,
      final List<TransactionReceipt> receipts) {
    return importBlock(sealedBlock, blockAccessList);
  }
}

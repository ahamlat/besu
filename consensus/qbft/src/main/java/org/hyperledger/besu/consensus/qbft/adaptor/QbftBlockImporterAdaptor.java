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

import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockImporter;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BlockImportResult;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.PathBasedWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.PathBasedWorldStateUpdateAccumulator;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adaptor class to allow a {@link BlockImporter} to be used as a {@link QbftBlockImporter}. */
public class QbftBlockImporterAdaptor implements QbftBlockImporter {

  private static final Logger LOG = LoggerFactory.getLogger(QbftBlockImporterAdaptor.class);

  private final BlockImporter blockImporter;
  private final ProtocolContext context;
  private final Optional<QbftLocalBlockExecutionCache> executionCache;

  /**
   * Constructs a new Qbft block importer.
   *
   * @param blockImporter The Besu block importer
   * @param context The protocol context
   */
  public QbftBlockImporterAdaptor(
      final BlockImporter blockImporter, final ProtocolContext context) {
    this(blockImporter, context, Optional.empty());
  }

  /**
   * Constructs a new Qbft block importer.
   *
   * @param blockImporter The Besu block importer
   * @param context The protocol context
   * @param executionCache optional cache of local block execution results
   */
  public QbftBlockImporterAdaptor(
      final BlockImporter blockImporter,
      final ProtocolContext context,
      final Optional<QbftLocalBlockExecutionCache> executionCache) {
    this.blockImporter = blockImporter;
    this.context = context;
    this.executionCache = executionCache;
  }

  @Override
  public boolean importBlock(
      final QbftBlock block, final Optional<BlockAccessList> blockAccessList) {
    final BlockImportResult blockImportResult =
        blockImporter.importBlock(
            context,
            AdaptorUtil.toBesuBlock(block),
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            blockAccessList);
    return blockImportResult.isImported();
  }

  @Override
  public boolean importLocallyCreatedBlock(
      final QbftBlock sealedBlock,
      final QbftBlock proposedBlock,
      final Optional<BlockAccessList> blockAccessList,
      final List<TransactionReceipt> receipts) {
    if (executionCache.isEmpty()) {
      return importBlock(sealedBlock, blockAccessList);
    }

    final Optional<QbftLocalBlockExecutionCache.CachedExecution> cached =
        executionCache.get().take(proposedBlock.getHash());
    if (cached.isEmpty()) {
      LOG.debug(
          "No cached local execution for proposed block {}, using full import",
          proposedBlock.getHash());
      return importBlock(sealedBlock, blockAccessList);
    }

    final Block sealedBesuBlock = AdaptorUtil.toBesuBlock(sealedBlock);
    final MutableWorldState retainedWorldState = cached.get().worldState();
    try {
      if (context.getBlockchain().contains(sealedBesuBlock.getHash())) {
        return true;
      }
      persistLocalExecutionToHead(sealedBesuBlock, retainedWorldState);
      context.getBlockchain().appendBlock(sealedBesuBlock, receipts, blockAccessList);
      LOG.debug(
          "Imported locally created QBFT block {} without re-execution", sealedBesuBlock.getHash());
      return true;
    } catch (final Exception e) {
      LOG.warn("Fast import of locally created QBFT block failed, using full import", e);
      return importBlock(sealedBlock, blockAccessList);
    } finally {
      try {
        retainedWorldState.close();
      } catch (final Exception e) {
        LOG.debug("Failed to close retained world state after import", e);
      }
    }
  }

  /**
   * Writes the locally executed updates onto the head world state.
   *
   * <p>Block creation uses a frozen Bonsai copy. Persist of that copy does not update head storage,
   * and a later head roll through trie logs is slow. Copy the accumulator onto the live head and
   * persist there instead, matching normal import after a successful process step.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void persistLocalExecutionToHead(
      final Block sealedBesuBlock, final MutableWorldState retainedWorldState) {
    final MutableWorldState headWorldState = context.getWorldStateArchive().getWorldState();
    if (headWorldState instanceof PathBasedWorldState pathBasedHead
        && retainedWorldState instanceof PathBasedWorldState pathBasedRetained) {
      if (!pathBasedHead.blockHash().equals(sealedBesuBlock.getHeader().getParentHash())) {
        throw new IllegalStateException(
            "Head world state is not at parent "
                + sealedBesuBlock.getHeader().getParentHash()
                + ", cannot persist local QBFT execution");
      }
      final PathBasedWorldStateUpdateAccumulator headAccumulator = pathBasedHead.getAccumulator();
      headAccumulator.cloneFromUpdater(pathBasedRetained.getAccumulator());
      pathBasedHead.persist(sealedBesuBlock.getHeader());
    } else {
      retainedWorldState.persist(sealedBesuBlock.getHeader());
    }
  }
}

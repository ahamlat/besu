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

import org.hyperledger.besu.consensus.common.bft.BftBlockHeaderFunctions;
import org.hyperledger.besu.consensus.common.bft.BftExtraData;
import org.hyperledger.besu.consensus.common.bft.BftExtraDataCodec;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockCreator;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockHeader;
import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.ethereum.blockcreation.BlockCreationTiming;
import org.hyperledger.besu.ethereum.blockcreation.BlockCreator;
import org.hyperledger.besu.ethereum.blockcreation.txselection.TransactionSelectionResults;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Adaptor class to allow a {@link BlockCreator} to be used as a {@link QbftBlockCreator}. */
public class QbftBlockCreatorAdaptor implements QbftBlockCreator {

  private final BlockCreator besuBlockCreator;
  private final BftExtraDataCodec bftExtraDataCodec;
  private final Optional<QbftLocalBlockExecutionCache> executionCache;

  /**
   * Constructs a new QbftBlockCreator
   *
   * @param besuBftBlockCreator the Besu BFT block creator
   * @param bftExtraDataCodec the bftExtraDataCodec used to encode extra data for the new header
   */
  public QbftBlockCreatorAdaptor(
      final BlockCreator besuBftBlockCreator, final BftExtraDataCodec bftExtraDataCodec) {
    this(besuBftBlockCreator, bftExtraDataCodec, Optional.empty());
  }

  /**
   * Constructs a new QbftBlockCreator
   *
   * @param besuBftBlockCreator the Besu BFT block creator
   * @param bftExtraDataCodec the bftExtraDataCodec used to encode extra data for the new header
   * @param executionCache optional cache of local block execution results
   */
  public QbftBlockCreatorAdaptor(
      final BlockCreator besuBftBlockCreator,
      final BftExtraDataCodec bftExtraDataCodec,
      final Optional<QbftLocalBlockExecutionCache> executionCache) {
    this.besuBlockCreator = besuBftBlockCreator;
    this.bftExtraDataCodec = bftExtraDataCodec;
    this.executionCache = executionCache;
  }

  @Override
  public BlockCreationResult createBlock(
      final long headerTimeStampSeconds, final QbftBlockHeader parentHeader) {
    var blockResult =
        besuBlockCreator.createBlock(
            headerTimeStampSeconds, AdaptorUtil.toBesuBlockHeader(parentHeader));
    final List<TransactionReceipt> receipts =
        Optional.ofNullable(blockResult.getTransactionSelectionResults())
            .map(TransactionSelectionResults::getReceipts)
            .orElse(List.of());
    blockResult
        .getWorldState()
        .ifPresent(
            worldState ->
                executionCache.ifPresent(
                    cache ->
                        cache.store(
                            new QbftLocalBlockExecutionCache.CachedExecution(
                                blockResult.getBlock().getHash(), worldState, receipts))));
    return new BlockCreationResult(
        new QbftBlockAdaptor(
            blockResult.getBlock(), Optional.of(blockResult.getBlockCreationTimings())),
        blockResult.getBlockAccessList(),
        receipts);
  }

  @Override
  public QbftBlock createSealedBlock(
      final QbftBlock block, final int roundNumber, final Collection<SECPSignature> commitSeals) {
    final Block besuBlock = AdaptorUtil.toBesuBlock(block);
    final QbftBlockHeader initialHeader = block.getHeader();
    final BftExtraData initialExtraData =
        bftExtraDataCodec.decode(AdaptorUtil.toBesuBlockHeader(initialHeader));

    final BftExtraData sealedExtraData =
        new BftExtraData(
            initialExtraData.getVanityData(),
            commitSeals,
            initialExtraData.getVote(),
            roundNumber,
            initialExtraData.getValidators());

    final BlockHeader sealedHeader =
        BlockHeaderBuilder.fromHeader(AdaptorUtil.toBesuBlockHeader(initialHeader))
            .extraData(bftExtraDataCodec.encode(sealedExtraData))
            .blockHeaderFunctions(BftBlockHeaderFunctions.forOnchainBlock(bftExtraDataCodec))
            .buildBlockHeader();
    final Block sealedBesuBlock = new Block(sealedHeader, besuBlock.getBody());
    final Optional<BlockCreationTiming> timing =
        block instanceof QbftBlockAdaptor adaptor
            ? adaptor.getBlockCreationTiming()
            : Optional.empty();
    return new QbftBlockAdaptor(sealedBesuBlock, timing);
  }
}

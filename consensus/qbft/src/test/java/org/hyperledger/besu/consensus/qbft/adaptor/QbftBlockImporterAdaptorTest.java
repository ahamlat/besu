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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.ALREADY_IMPORTED;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.IMPORTED;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.NOT_IMPORTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.mainnet.BlockImportResult;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QbftBlockImporterAdaptorTest {
  @Mock private BlockImporter blockImporter;
  @Mock private ProtocolContext protocolContext;
  private final Block besuBlock = new BlockDataGenerator().block();
  private final QbftBlock block = new QbftBlockAdaptor(besuBlock);

  @Test
  void importsBlockSuccessfullyWhenBesuBlockImports() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);
  }

  @Test
  void importsBlockSuccessfullyWhenBesuBlockAlreadyImported() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(ALREADY_IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);
  }

  @Test
  void importsBlockFailsWhenBesuBlockNotImported() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(NOT_IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(false);
  }

  @Test
  void importsLocallyCreatedBlockWithoutReExecution() throws Exception {
    final MutableBlockchain blockchain = org.mockito.Mockito.mock(MutableBlockchain.class);
    final WorldStateArchive worldStateArchive = org.mockito.Mockito.mock(WorldStateArchive.class);
    final MutableWorldState worldState = org.mockito.Mockito.mock(MutableWorldState.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    when(blockchain.contains(besuBlock.getHash())).thenReturn(false);

    final QbftLocalBlockExecutionCache cache = new QbftLocalBlockExecutionCache();
    cache.store(
        new QbftLocalBlockExecutionCache.CachedExecution(block.getHash(), worldState, List.of()));

    final QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, Optional.of(cache));
    assertThat(
            qbftBlockImporter.importLocallyCreatedBlock(block, block, Optional.empty(), List.of()))
        .isTrue();

    verify(worldState).persist(besuBlock.getHeader());
    verify(blockchain).appendBlock(besuBlock, List.of(), Optional.empty());
    verify(worldStateArchive).getWorldState(any());
    verify(worldState).close();
    verify(blockImporter, never()).importBlock(any(), any(), any(), any(), any());
  }

  @Test
  void fallsBackToFullImportWhenLocalExecutionIsNotCached() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(IMPORTED));

    final QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(
            blockImporter, protocolContext, Optional.of(new QbftLocalBlockExecutionCache()));
    assertThat(
            qbftBlockImporter.importLocallyCreatedBlock(block, block, Optional.empty(), List.of()))
        .isTrue();
    verify(blockImporter)
        .importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty());
  }
}

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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import static org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.WorldStateConfig.createStatefulConfigWithTrie;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList.BlockAccessListBuilder;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.PartialBlockAccessView;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoOpBonsaiCachedWorldStorageManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoopBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.testutil.MockExecutorService;

import java.util.List;
import java.util.Collections;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParallelizedConcurrentTransactionProcessorTest {

  @Mock private MainnetTransactionProcessor transactionProcessor;
  @Mock private BlockHeader chainHeadBlockHeader;
  @Mock private BlockHeader blockHeader;
  @Mock ProtocolContext protocolContext;
  @Mock private Transaction transaction;
  @Mock private TransactionCollisionDetector transactionCollisionDetector;

  private BonsaiWorldState worldState;

  private ParallelizedConcurrentTransactionProcessor processor;

  @BeforeEach
  void setUp() {
    processor =
        new ParallelizedConcurrentTransactionProcessor(
            transactionProcessor, transactionCollisionDetector);
    final BonsaiWorldStateKeyValueStorage bonsaiWorldStateKeyValueStorage =
        new BonsaiWorldStateKeyValueStorage(
            new InMemoryKeyValueStorageProvider(),
            new NoOpMetricsSystem(),
            DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
    worldState =
        new BonsaiWorldState(
            bonsaiWorldStateKeyValueStorage,
            new NoopBonsaiCachedMerkleTrieLoader(),
            new NoOpBonsaiCachedWorldStorageManager(
                bonsaiWorldStateKeyValueStorage, EvmConfiguration.DEFAULT, new CodeCache()),
            new NoOpTrieLogManager(),
            EvmConfiguration.DEFAULT,
            createStatefulConfigWithTrie(),
            new CodeCache());

    lenient().when(chainHeadBlockHeader.getHash()).thenReturn(Hash.ZERO);
    lenient().when(chainHeadBlockHeader.getStateRoot()).thenReturn(Hash.EMPTY_TRIE_HASH);
    lenient().when(blockHeader.getParentHash()).thenReturn(Hash.ZERO);

    lenient().when(transaction.detachedCopy()).thenReturn(transaction);

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    lenient().when(protocolContext.getBlockchain()).thenReturn(blockchain);
    lenient().when(blockchain.getChainHeadHeader()).thenReturn(chainHeadBlockHeader);
    final WorldStateArchive worldStateArchive = mock(WorldStateArchive.class);
    lenient().when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    lenient().when(worldStateArchive.getWorldState(any())).thenReturn(Optional.of(worldState));
    lenient().when(transactionCollisionDetector.hasCollision(any(), any(), any(), any()))
        .thenReturn(false);
  }

  @Test
  void testRunTransaction() {
    Address miningBeneficiary = Address.fromHexString("0x1");
    Wei blobGasPrice = Wei.ZERO;

    Mockito.when(
            transactionProcessor.processTransaction(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            TransactionProcessingResult.successful(
                Collections.emptyList(),
                0,
                0,
                Bytes.EMPTY,
                Optional.empty(),
                ValidationResult.valid()));

    processor.runTransaction(
        protocolContext,
        blockHeader,
        0,
        transaction,
        miningBeneficiary,
        (__, ___) -> Hash.EMPTY,
        blobGasPrice,
        Optional.empty());

    verify(transactionProcessor, times(1))
        .processTransaction(
            any(WorldUpdater.class),
            eq(blockHeader),
            eq(transaction),
            eq(miningBeneficiary),
            any(OperationTracer.class),
            any(BlockHashLookup.class),
            eq(TransactionValidationParams.processingBlock()),
            eq(blobGasPrice),
            eq(Optional.empty()));

    assertTrue(
        processor
            .applyParallelizedTransactionResult(
                worldState, miningBeneficiary, transaction, 0, Optional.empty(), Optional.empty())
            .isPresent(),
        "Expected the transaction context to be stored");
  }

  @Test
  void testRunTransactionWithFailure() {
    Address miningBeneficiary = Address.fromHexString("0x1");
    Wei blobGasPrice = Wei.ZERO;

    when(transactionProcessor.processTransaction(
            any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            TransactionProcessingResult.failed(
                0,
                0,
                ValidationResult.invalid(
                    TransactionInvalidReason.BLOB_GAS_PRICE_BELOW_CURRENT_BLOB_BASE_FEE),
                Optional.of(Bytes.EMPTY),
                Optional.empty(),
                Optional.empty()));

    processor.runTransaction(
        protocolContext,
        blockHeader,
        0,
        transaction,
        miningBeneficiary,
        (__, ___) -> Hash.EMPTY,
        blobGasPrice,
        Optional.empty());

    Optional<TransactionProcessingResult> result =
        processor.applyParallelizedTransactionResult(
            worldState, miningBeneficiary, transaction, 0, Optional.empty(), Optional.empty());
    assertTrue(result.isEmpty(), "Expected the transaction result to indicate a failure");
  }

  @Test
  void testRunTransactionWithConflict() {

    Address miningBeneficiary = Address.fromHexString("0x1");
    Wei blobGasPrice = Wei.ZERO;

    Mockito.when(
            transactionProcessor.processTransaction(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            TransactionProcessingResult.successful(
                Collections.emptyList(),
                0,
                0,
                Bytes.EMPTY,
                Optional.empty(),
                ValidationResult.valid()));

    processor.runTransaction(
        protocolContext,
        blockHeader,
        0,
        transaction,
        miningBeneficiary,
        (__, ___) -> Hash.EMPTY,
        blobGasPrice,
        Optional.empty());

    verify(transactionProcessor, times(1))
        .processTransaction(
            any(WorldUpdater.class),
            eq(blockHeader),
            eq(transaction),
            eq(miningBeneficiary),
            any(OperationTracer.class),
            any(BlockHashLookup.class),
            eq(TransactionValidationParams.processingBlock()),
            eq(blobGasPrice),
            eq(Optional.empty()));

    // simulate a conflict
    when(transactionCollisionDetector.hasCollision(any(), any(), any(), any())).thenReturn(true);

    Optional<TransactionProcessingResult> result =
        processor.applyParallelizedTransactionResult(
            worldState, miningBeneficiary, transaction, 0, Optional.empty(), Optional.empty());
    assertTrue(result.isEmpty(), "Expected no transaction result to be applied due to conflict");
  }

  @Test
  void testApplyResultUsesAccessLocationTrackerAndUpdatesPartialBlockAccessView() {
    Address miningBeneficiary = Address.fromHexString("0x1");
    Wei blobGasPrice = Wei.ZERO;

    PartialBlockAccessView partialView = mock(PartialBlockAccessView.class);
    PartialBlockAccessView.AccountChanges beneficiaryChanges =
        mock(PartialBlockAccessView.AccountChanges.class);
    when(beneficiaryChanges.getAddress()).thenReturn(miningBeneficiary);
    when(partialView.accountChanges()).thenReturn(Collections.singletonList(beneficiaryChanges));

    when(transactionProcessor.processTransaction(
            any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            TransactionProcessingResult.successful(
                Collections.emptyList(),
                0,
                0,
                Bytes.EMPTY,
                Optional.of(partialView),
                ValidationResult.valid()));

    BlockAccessListBuilder balBuilder = mock(BlockAccessListBuilder.class);

    processor.runTransaction(
        protocolContext,
        blockHeader,
        0,
        transaction,
        miningBeneficiary,
        (__, ___) -> Hash.EMPTY,
        blobGasPrice,
        Optional.of(balBuilder));

    verify(transactionProcessor)
        .processTransaction(
            any(WorldUpdater.class),
            eq(blockHeader),
            eq(transaction),
            eq(miningBeneficiary),
            any(OperationTracer.class),
            any(BlockHashLookup.class),
            eq(TransactionValidationParams.processingBlock()),
            eq(blobGasPrice),
            argThat(Optional::isPresent));

    when(transactionCollisionDetector.hasCollision(any(), any(), any(), any())).thenReturn(false);

    Optional<TransactionProcessingResult> maybeResult =
        processor.applyParallelizedTransactionResult(
            worldState, miningBeneficiary, transaction, 0, Optional.empty(), Optional.empty());

    assertTrue(
        maybeResult.isPresent(), "Expected the parallelized transaction result to be applied");
    TransactionProcessingResult result = maybeResult.get();
    assertTrue(result.getPartialBlockAccessView().isPresent(), "Expected BAL view to be present");
    verify(beneficiaryChanges).setPostBalance(any(Wei.class));
  }

  @Test
  void testRunAsyncBlockSchedulesBoundedWorkAndCancelsWhenSequentialOvertakes() {
    final Address miningBeneficiary = Address.fromHexString("0x1");
    final Wei blobGasPrice = Wei.ZERO;

    final MockExecutorService mockExecutor = new MockExecutorService();
    mockExecutor.setAutoRun(false); // keep tasks pending so apply() will attempt to cancel

    final Transaction tx0 = mock(Transaction.class);
    final Transaction tx1 = mock(Transaction.class);

    processor.runAsyncBlock(
        protocolContext,
        blockHeader,
        List.of(tx0, tx1),
        miningBeneficiary,
        (__, ___) -> Hash.EMPTY,
        blobGasPrice,
        mockExecutor,
        1, // maxInFlightTransactions
        Optional.empty());

    // With maxInFlight=1, only the first transaction should be scheduled initially.
    assertTrue(mockExecutor.getFutures().size() == 1, "Expected one background task scheduled");

    processor.applyParallelizedTransactionResult(
        worldState, miningBeneficiary, tx0, 0, Optional.empty(), Optional.empty());

    // When the sequential processor overtakes tx0, we should cancel its background task.
    verify(mockExecutor.getFutures().get(0)).cancel(true);

    // And we should schedule the next tx to maintain the in-flight window.
    assertTrue(mockExecutor.getFutures().size() == 2, "Expected the next background task scheduled");
  }
}

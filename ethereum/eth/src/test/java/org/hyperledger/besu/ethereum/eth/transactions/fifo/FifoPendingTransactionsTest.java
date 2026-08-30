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
package org.hyperledger.besu.ethereum.eth.transactions.fifo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction.MAX_SCORE;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.TX_POOL_FULL;
import static org.hyperledger.besu.plugin.data.TransactionSelectionResult.SELECTED;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.core.Util;
import org.hyperledger.besu.ethereum.eth.transactions.ImmutableTransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.metrics.StubMetricsSystem;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class FifoPendingTransactionsTest {

  private static final int MAX_TRANSACTIONS = 10;
  private static final int MAX_FUTURE_BY_SENDER = 3;
  private static final SignatureAlgorithm SIGNATURE_ALGORITHM =
      SignatureAlgorithmFactory.getInstance();
  private static final KeyPair KEYS1 = SIGNATURE_ALGORITHM.generateKeyPair();
  private static final KeyPair KEYS2 = SIGNATURE_ALGORITHM.generateKeyPair();
  private static final Address SENDER1 = Util.publicKeyToAddress(KEYS1.getPublicKey());

  private FifoPendingTransactions transactions;

  @BeforeEach
  public void setup() {
    transactions = createPool(MAX_TRANSACTIONS, MAX_FUTURE_BY_SENDER);
  }

  private FifoPendingTransactions createPool(final int maxSize, final int maxFutureBySender) {
    final TransactionPoolConfiguration poolConfig =
        ImmutableTransactionPoolConfiguration.builder()
            .txPoolMaxSize(maxSize)
            .maxFutureBySender(maxFutureBySender)
            .build();
    return new FifoPendingTransactions(poolConfig, new StubMetricsSystem());
  }

  @Test
  public void shouldAddAndSelectInArrivalOrderAcrossSenders() {
    final Transaction tx1Sender1 = createTransaction(0, KEYS1);
    final Transaction tx1Sender2 = createTransaction(0, KEYS2);
    final Transaction tx2Sender1 = createTransaction(1, KEYS1);

    assertThat(transactions.addTransaction(remoteTx(tx1Sender1), Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(transactions.addTransaction(remoteTx(tx1Sender2), Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(transactions.addTransaction(remoteTx(tx2Sender1), Optional.empty()))
        .isEqualTo(ADDED);

    assertThat(selectCandidates()).containsExactly(tx1Sender1, tx1Sender2, tx2Sender1);
  }

  @Test
  public void shouldBufferOutOfOrderArrivalAndPromoteWhenGapFilled() {
    // spamoor scenario: concurrent requests arrive out of order
    final Transaction tx0 = createTransaction(0, KEYS1);
    final Transaction tx1 = createTransaction(1, KEYS1);
    final Transaction tx2 = createTransaction(2, KEYS1);

    assertThat(transactions.addTransaction(remoteTx(tx2), Optional.empty())).isEqualTo(ADDED);
    assertThat(transactions.addTransaction(remoteTx(tx0), Optional.empty())).isEqualTo(ADDED);

    // tx2 is buffered, only tx0 is ready
    assertThat(selectCandidates()).containsExactly(tx0);
    assertThat(transactions.size()).isEqualTo(2);

    // filling the gap promotes tx2
    assertThat(transactions.addTransaction(remoteTx(tx1), Optional.empty())).isEqualTo(ADDED);
    assertThat(selectCandidates()).containsExactly(tx0, tx1, tx2);
  }

  @Test
  public void shouldRejectWhenPoolIsFull() {
    final FifoPendingTransactions smallPool = createPool(2, MAX_FUTURE_BY_SENDER);
    assertThat(smallPool.addTransaction(remoteTx(createTransaction(0, KEYS1)), Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(smallPool.addTransaction(remoteTx(createTransaction(1, KEYS1)), Optional.empty()))
        .isEqualTo(ADDED);

    assertThat(smallPool.addTransaction(remoteTx(createTransaction(0, KEYS2)), Optional.empty()))
        .isEqualTo(TX_POOL_FULL);
  }

  @Test
  public void shouldRejectSameNonceReplacementAndDuplicates() {
    final Transaction tx0 = createTransaction(0, KEYS1);
    assertThat(transactions.addTransaction(remoteTx(tx0), Optional.empty())).isEqualTo(ADDED);

    // identical tx
    assertThat(transactions.addTransaction(remoteTx(tx0), Optional.empty()))
        .isEqualTo(ALREADY_KNOWN);

    // different tx, same nonce, ready
    final Transaction tx0Replacement =
        new TransactionTestFixture().nonce(0).value(Wei.of(999)).createTransaction(KEYS1);
    assertThat(transactions.addTransaction(remoteTx(tx0Replacement), Optional.empty()))
        .isEqualTo(REJECTED_UNDERPRICED_REPLACEMENT);

    // different tx, same nonce, future buffered
    final Transaction tx3 = createTransaction(3, KEYS1);
    final Transaction tx3Replacement =
        new TransactionTestFixture().nonce(3).value(Wei.of(999)).createTransaction(KEYS1);
    assertThat(transactions.addTransaction(remoteTx(tx3), Optional.empty())).isEqualTo(ADDED);
    assertThat(transactions.addTransaction(remoteTx(tx3Replacement), Optional.empty()))
        .isEqualTo(REJECTED_UNDERPRICED_REPLACEMENT);
  }

  @Test
  public void shouldRejectNonceTooFarInFuture() {
    assertThat(transactions.addTransaction(remoteTx(createTransaction(0, KEYS1)), Optional.empty()))
        .isEqualTo(ADDED);

    // MAX_FUTURE_BY_SENDER is 3, so nonce 1 + 3 = 4 is the last acceptable one
    assertThat(transactions.addTransaction(remoteTx(createTransaction(4, KEYS1)), Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(transactions.addTransaction(remoteTx(createTransaction(9, KEYS1)), Optional.empty()))
        .isEqualTo(NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER);
  }

  @Test
  public void shouldRemoveConfirmedTransactionsAndPromoteBuffered() {
    final Transaction tx0 = createTransaction(0, KEYS1);
    final Transaction tx1 = createTransaction(1, KEYS1);
    final Transaction tx3 = createTransaction(3, KEYS1);

    transactions.addTransaction(remoteTx(tx0), Optional.empty());
    transactions.addTransaction(remoteTx(tx1), Optional.empty());
    transactions.addTransaction(remoteTx(tx3), Optional.empty());
    assertThat(transactions.size()).isEqualTo(3);

    // a block confirms nonces 0..2 (nonce 2 was never in the pool)
    final Transaction confirmedTx2 = createTransaction(2, KEYS1);
    transactions.manageBlockAdded(
        Mockito.mock(BlockHeader.class), List.of(tx0, tx1, confirmedTx2), List.of(), null);

    // tx3 became contiguous and is now ready
    assertThat(transactions.size()).isEqualTo(1);
    assertThat(selectCandidates()).containsExactly(tx3);
  }

  @Test
  public void shouldDropStaleTransactionsWhenCompetingTransactionConfirmed() {
    final Transaction tx0 = createTransaction(0, KEYS1);
    transactions.addTransaction(remoteTx(tx0), Optional.empty());

    // another validator included a different tx with the same nonce
    final Transaction competingTx0 =
        new TransactionTestFixture().nonce(0).value(Wei.of(999)).createTransaction(KEYS1);
    transactions.manageBlockAdded(
        Mockito.mock(BlockHeader.class), List.of(competingTx0), List.of(), null);

    assertThat(transactions.size()).isEqualTo(0);
    assertThat(transactions.getTransactionByHash(tx0.getHash())).isEmpty();
  }

  @Test
  public void shouldDemoteHigherNoncesWhenTransactionDiscarded() {
    final Transaction tx0 = createTransaction(0, KEYS1);
    final Transaction tx1 = createTransaction(1, KEYS1);
    final Transaction tx2 = createTransaction(2, KEYS1);

    transactions.addTransaction(remoteTx(tx0), Optional.empty());
    transactions.addTransaction(remoteTx(tx1), Optional.empty());
    transactions.addTransaction(remoteTx(tx2), Optional.empty());

    // the selector discards tx1 as permanently invalid
    transactions.selectTransactions(
        candidates ->
            candidates.stream()
                .collect(
                    Collectors.toMap(
                        tx -> tx,
                        tx ->
                            tx.getNonce() == 1
                                ? TransactionSelectionResult.invalid("test")
                                : SELECTED)));

    // tx1 is gone, tx2 is demoted to the future buffer, only tx0 is ready
    assertThat(transactions.getTransactionByHash(tx1.getHash())).isEmpty();
    assertThat(selectCandidates()).containsExactly(tx0);
    assertThat(transactions.size()).isEqualTo(2);

    // a replacement for nonce 1 fills the gap again and tx2 gets promoted
    final Transaction tx1Replacement =
        new TransactionTestFixture().nonce(1).value(Wei.of(999)).createTransaction(KEYS1);
    assertThat(transactions.addTransaction(remoteTx(tx1Replacement), Optional.empty()))
        .isEqualTo(ADDED);
    assertThat(selectCandidates()).containsExactly(tx0, tx1Replacement, tx2);
  }

  @Test
  public void shouldTrackNextNonceForSender() {
    assertThat(transactions.getNextNonceForSender(SENDER1)).isEqualTo(OptionalLong.empty());

    transactions.addTransaction(remoteTx(createTransaction(0, KEYS1)), Optional.empty());
    transactions.addTransaction(remoteTx(createTransaction(1, KEYS1)), Optional.empty());
    assertThat(transactions.getNextNonceForSender(SENDER1)).isEqualTo(OptionalLong.of(2));
  }

  @Test
  public void shouldStartFromAccountNonce() {
    final Account account = Mockito.mock(Account.class);
    Mockito.when(account.getNonce()).thenReturn(5L);

    final Transaction tx5 = createTransaction(5, KEYS1);
    assertThat(transactions.addTransaction(remoteTx(tx5), Optional.of(account))).isEqualTo(ADDED);
    assertThat(selectCandidates()).containsExactly(tx5);
  }

  @Test
  public void shouldReportStatusOfReadyAndFutureTransactions() {
    transactions.addTransaction(remoteTx(createTransaction(0, KEYS1)), Optional.empty());
    transactions.addTransaction(remoteTx(createTransaction(3, KEYS1)), Optional.empty());

    final var status = transactions.getStatus();
    assertThat(status.pendingCount()).isEqualTo(1);
    assertThat(status.queuedCount()).isEqualTo(1);
  }

  @Test
  public void shouldHandleConcurrentAddsFromManySenders() throws InterruptedException {
    final int senderCount = 8;
    final int txPerSender = 50;
    final FifoPendingTransactions bigPool = createPool(senderCount * txPerSender, txPerSender);

    final Map<Integer, KeyPair> keys = new HashMap<>();
    final Map<Integer, List<Transaction>> txsBySender = new HashMap<>();
    for (int i = 0; i < senderCount; i++) {
      final KeyPair keyPair = SIGNATURE_ALGORITHM.generateKeyPair();
      keys.put(i, keyPair);
      final List<Transaction> txs = new ArrayList<>();
      for (int nonce = 0; nonce < txPerSender; nonce++) {
        txs.add(createTransaction(nonce, keyPair));
      }
      txsBySender.put(i, txs);
    }

    final ExecutorService executor = Executors.newFixedThreadPool(senderCount);
    final CountDownLatch done = new CountDownLatch(senderCount);
    for (int i = 0; i < senderCount; i++) {
      final List<Transaction> txs = txsBySender.get(i);
      executor.submit(
          () -> {
            try {
              txs.forEach(tx -> bigPool.addTransaction(remoteTx(tx), Optional.empty()));
            } finally {
              done.countDown();
            }
          });
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    assertThat(bigPool.size()).isEqualTo(senderCount * txPerSender);

    // all txs are ready and, per sender, in nonce order
    final List<Transaction> candidates = new ArrayList<>();
    bigPool.selectTransactions(
        txs -> {
          txs.forEach(tx -> candidates.add(tx.getTransaction()));
          return Map.of();
        });
    assertThat(candidates).hasSize(senderCount * txPerSender);
    for (int i = 0; i < senderCount; i++) {
      final Address sender = Util.publicKeyToAddress(keys.get(i).getPublicKey());
      final List<Transaction> senderCandidates =
          candidates.stream().filter(tx -> tx.getSender().equals(sender)).toList();
      assertThat(senderCandidates).containsExactlyElementsOf(txsBySender.get(i));
    }
  }

  private List<Transaction> selectCandidates() {
    final List<Transaction> candidates = new ArrayList<>();
    transactions.selectTransactions(
        txs -> {
          txs.forEach(tx -> candidates.add(tx.getTransaction()));
          return Map.of();
        });
    return candidates;
  }

  private Transaction createTransaction(final long nonce, final KeyPair keyPair) {
    return new TransactionTestFixture().nonce(nonce).gasPrice(Wei.of(0)).createTransaction(keyPair);
  }

  private PendingTransaction remoteTx(final Transaction transaction) {
    return PendingTransaction.newPendingTransaction(transaction, false, false, MAX_SCORE);
  }
}

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

import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.TX_POOL_FULL;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionAddedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions;
import org.hyperledger.besu.ethereum.eth.transactions.RemovalReason;
import org.hyperledger.besu.ethereum.eth.transactions.SenderPendingTransactionsData;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.util.Subscribers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pending transactions implementation optimized for free-gas, permissioned networks (e.g. QBFT)
 * with high transaction throughput.
 *
 * <p>Transactions that are executable (contiguous nonce) are kept in a global ready queue, ordered
 * by arrival. There is no fee-based sorting, no fee-based replacement and no timed eviction: when
 * the pool is full new transactions are rejected, so the sender gets an explicit error.
 *
 * <p>Transactions with a future nonce are kept in a small bounded per-sender buffer and promoted to
 * the ready queue as soon as the nonce gap is filled. This absorbs out-of-order arrival of
 * transactions sent concurrently by load generators.
 *
 * <p>Thread-safety: lookup maps and the ready queue are concurrent collections; mutations of the
 * state of a single sender are serialized with a per-sender lock, so different senders never
 * contend on a common lock.
 */
public class FifoPendingTransactions implements PendingTransactions {
  private static final Logger LOG = LoggerFactory.getLogger(FifoPendingTransactions.class);

  private final TransactionPoolConfiguration poolConfig;

  private final Map<Hash, PendingTransaction> pendingTransactions;
  private final ConcurrentHashMap<Address, SenderState> senderStates = new ConcurrentHashMap<>();

  /** Ready (executable) transactions in arrival order, keyed by a monotonic sequence. */
  private final ConcurrentSkipListMap<Long, PendingTransaction> readyQueue =
      new ConcurrentSkipListMap<>();

  private final AtomicLong readySequence = new AtomicLong();

  private final Subscribers<PendingTransactionAddedListener> addedListeners = Subscribers.create();
  private final Subscribers<PendingTransactionDroppedListener> droppedListeners =
      Subscribers.create();

  private final Counter localTransactionAddedCounter;
  private final Counter remoteTransactionAddedCounter;
  private final LabelledMetric<Counter> transactionRemovedCounter;

  public FifoPendingTransactions(
      final TransactionPoolConfiguration poolConfig, final MetricsSystem metricsSystem) {
    this.poolConfig = poolConfig;
    this.pendingTransactions = new ConcurrentHashMap<>(poolConfig.getTxPoolMaxSize());

    final LabelledMetric<Counter> transactionAddedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_added_total",
            "Count of transactions added to the transaction pool",
            "source");
    localTransactionAddedCounter = transactionAddedCounter.labels("local");
    remoteTransactionAddedCounter = transactionAddedCounter.labels("remote");

    transactionRemovedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_removed_total",
            "Count of transactions removed from the transaction pool",
            "source",
            "operation");

    metricsSystem.createIntegerGauge(
        BesuMetricCategory.TRANSACTION_POOL,
        "transactions",
        "Current size of the transaction pool",
        pendingTransactions::size);
  }

  /** Per-sender bookkeeping, guarded by its own monitor. */
  private static final class SenderState {
    /** The nonce the next ready transaction must have. */
    long nextReadyNonce;

    /** Nonce to ready-queue sequence, for the sender's ready transactions. */
    final NavigableMap<Long, Long> readySeqByNonce = new TreeMap<>();

    /** Future (non-contiguous nonce) transactions, bounded by maxFutureBySender. */
    final NavigableMap<Long, PendingTransaction> future = new TreeMap<>();

    /** Set when this state has been removed from the map, forcing adders to retry. */
    boolean removed;

    SenderState(final long nextReadyNonce) {
      this.nextReadyNonce = nextReadyNonce;
    }

    boolean isEmpty() {
      return readySeqByNonce.isEmpty() && future.isEmpty();
    }
  }

  @Override
  public TransactionAddedResult addTransaction(
      final PendingTransaction pendingTransaction, final Optional<Account> maybeSenderAccount) {

    if (pendingTransactions.containsKey(pendingTransaction.getHash())) {
      return ALREADY_KNOWN;
    }

    if (pendingTransactions.size() >= poolConfig.getTxPoolMaxSize()) {
      return TX_POOL_FULL;
    }

    final long stateNonce = maybeSenderAccount.map(AccountState::getNonce).orElse(0L);
    final Address sender = pendingTransaction.getSender();

    TransactionAddedResult result;

    while (true) {
      final SenderState senderState =
          senderStates.computeIfAbsent(sender, unused -> new SenderState(stateNonce));
      synchronized (senderState) {
        if (senderState.removed) {
          // lost a race with a concurrent cleanup, retry with a fresh state
          continue;
        }
        result = internalAdd(senderState, pendingTransaction, stateNonce);
        break;
      }
    }

    if (result.isSuccess()) {
      if (pendingTransaction.isReceivedFromLocalSource()) {
        localTransactionAddedCounter.inc();
      } else {
        remoteTransactionAddedCounter.inc();
      }
      notifyTransactionAdded(pendingTransaction.getTransaction());
    }
    return result;
  }

  private TransactionAddedResult internalAdd(
      final SenderState senderState,
      final PendingTransaction pendingTransaction,
      final long stateNonce) {

    // if the world state moved forward while the sender had no ready txs, catch up
    if (stateNonce > senderState.nextReadyNonce && senderState.readySeqByNonce.isEmpty()) {
      senderState.nextReadyNonce = stateNonce;
    }

    final long nonce = pendingTransaction.getNonce();

    if (nonce < senderState.nextReadyNonce) {
      // the nonce is already taken by a ready transaction (or already confirmed on chain):
      // replacement is not supported in the FIFO pool
      return REJECTED_UNDERPRICED_REPLACEMENT;
    }

    if (nonce == senderState.nextReadyNonce) {
      appendReady(senderState, pendingTransaction);
      promoteContiguous(senderState);
      return ADDED;
    }

    // future nonce
    if (senderState.future.containsKey(nonce)) {
      return REJECTED_UNDERPRICED_REPLACEMENT;
    }
    if (senderState.future.size() >= poolConfig.getMaxFutureBySender()
        || nonce - senderState.nextReadyNonce > poolConfig.getMaxFutureBySender()) {
      return NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
    }
    senderState.future.put(nonce, pendingTransaction);
    pendingTransactions.put(pendingTransaction.getHash(), pendingTransaction);
    return ADDED;
  }

  private void appendReady(final SenderState senderState, final PendingTransaction tx) {
    final long seq = readySequence.getAndIncrement();
    readyQueue.put(seq, tx);
    senderState.readySeqByNonce.put(tx.getNonce(), seq);
    senderState.nextReadyNonce = tx.getNonce() + 1;
    pendingTransactions.put(tx.getHash(), tx);
  }

  /** Moves buffered future transactions that became contiguous into the ready queue. */
  private void promoteContiguous(final SenderState senderState) {
    while (!senderState.future.isEmpty()
        && senderState.future.firstKey() == senderState.nextReadyNonce) {
      appendReady(senderState, senderState.future.remove(senderState.future.firstKey()));
    }
  }

  @Override
  public void selectTransactions(final PendingTransactionsSelector selector) {
    // weakly consistent snapshot in arrival order, no lock held during evaluation
    final List<PendingTransaction> candidates = new ArrayList<>(readyQueue.values());

    final var selectionResults = selector.evaluatePendingTransactions(candidates);

    for (final var selectionResult : selectionResults.entrySet()) {
      if (selectionResult.getValue().discard()) {
        removeInvalid(selectionResult.getKey());
      }
    }
  }

  /**
   * Removes a transaction marked invalid by the block selector. The sender's ready transactions
   * with a higher nonce are no longer executable, so they are demoted back to the future buffer and
   * will be promoted again if a valid transaction fills the gap.
   */
  private void removeInvalid(final PendingTransaction invalidTx) {
    final SenderState senderState = senderStates.get(invalidTx.getSender());
    if (senderState == null) {
      return;
    }
    final List<PendingTransaction> overflow = new ArrayList<>();
    synchronized (senderState) {
      final Long seq = senderState.readySeqByNonce.remove(invalidTx.getNonce());
      if (seq == null) {
        return;
      }
      readyQueue.remove(seq);
      pendingTransactions.remove(invalidTx.getHash());

      // demote higher-nonce ready txs of this sender back to the future buffer
      final var higherReady = senderState.readySeqByNonce.tailMap(invalidTx.getNonce(), false);
      for (final var entry : new ArrayList<>(higherReady.entrySet())) {
        final PendingTransaction demoted = readyQueue.remove(entry.getValue());
        higherReady.remove(entry.getKey());
        if (demoted != null) {
          if (senderState.future.size() < poolConfig.getMaxFutureBySender()) {
            senderState.future.put(demoted.getNonce(), demoted);
          } else {
            pendingTransactions.remove(demoted.getHash());
            overflow.add(demoted);
          }
        }
      }
      senderState.nextReadyNonce = invalidTx.getNonce();
      maybeRemoveSenderState(invalidTx.getSender(), senderState);
    }
    notifyTransactionDropped(invalidTx, FifoRemovalReason.INVALID);
    overflow.forEach(tx -> notifyTransactionDropped(tx, FifoRemovalReason.FUTURE_BUFFER_OVERFLOW));
    LOG.atTrace()
        .setMessage("Removed invalid transaction {}")
        .addArgument(invalidTx::toTraceLog)
        .log();
  }

  @Override
  public void manageBlockAdded(
      final BlockHeader blockHeader,
      final List<Transaction> confirmedTransactions,
      final List<Transaction> reorgTransactions,
      final FeeMarket feeMarket) {

    final Map<Address, Long> maxConfirmedNonceBySender = new HashMap<>();
    for (final Transaction tx : confirmedTransactions) {
      maxConfirmedNonceBySender.merge(tx.getSender(), tx.getNonce(), Math::max);
    }

    maxConfirmedNonceBySender.forEach(this::confirmed);
  }

  private void confirmed(final Address sender, final long maxConfirmedNonce) {
    final SenderState senderState = senderStates.get(sender);
    if (senderState == null) {
      return;
    }
    final List<PendingTransaction> stale = new ArrayList<>();
    synchronized (senderState) {
      final long newExpectedNonce = maxConfirmedNonce + 1;

      // remove ready txs with a confirmed nonce: normally the confirmed txs themselves, but
      // possibly competing txs with the same nonce included by another validator
      final var confirmedReady = senderState.readySeqByNonce.headMap(newExpectedNonce, false);
      for (final var entry : new ArrayList<>(confirmedReady.entrySet())) {
        final PendingTransaction removed = readyQueue.remove(entry.getValue());
        confirmedReady.remove(entry.getKey());
        if (removed != null) {
          pendingTransactions.remove(removed.getHash());
          transactionRemovedCounter
              .labels(removed.isReceivedFromLocalSource() ? "local" : "remote", "addedToBlock")
              .inc();
        }
      }

      // drop stale future txs
      final var staleFuture = senderState.future.headMap(newExpectedNonce, false);
      for (final var entry : new ArrayList<>(staleFuture.entrySet())) {
        staleFuture.remove(entry.getKey());
        pendingTransactions.remove(entry.getValue().getHash());
        stale.add(entry.getValue());
      }

      if (senderState.nextReadyNonce < newExpectedNonce) {
        senderState.nextReadyNonce = newExpectedNonce;
      }

      promoteContiguous(senderState);

      maybeRemoveSenderState(sender, senderState);
    }
    stale.forEach(tx -> notifyTransactionDropped(tx, FifoRemovalReason.CONFIRMED_STALE));
    LOG.atTrace()
        .setMessage("Confirmed transactions up to nonce {} for sender {}")
        .addArgument(maxConfirmedNonce)
        .addArgument(sender)
        .log();
  }

  /** Must be called while holding the sender state monitor. */
  private void maybeRemoveSenderState(final Address sender, final SenderState senderState) {
    if (senderState.isEmpty()) {
      senderState.removed = true;
      senderStates.remove(sender, senderState);
    }
  }

  @Override
  public void reset() {
    // no senders can be mutated concurrently during a reset by contract with the facade
    senderStates.clear();
    readyQueue.clear();
    pendingTransactions.clear();
  }

  @Override
  public void evictOldTransactions() {
    // no timed eviction: the pool rejects new transactions when full
  }

  @Override
  public List<Transaction> getLocalTransactions() {
    return pendingTransactions.values().stream()
        .filter(PendingTransaction::isReceivedFromLocalSource)
        .map(PendingTransaction::getTransaction)
        .collect(Collectors.toList());
  }

  @Override
  public List<Transaction> getPriorityTransactions() {
    return pendingTransactions.values().stream()
        .filter(PendingTransaction::hasPriority)
        .map(PendingTransaction::getTransaction)
        .collect(Collectors.toList());
  }

  @Override
  public long maxSize() {
    return poolConfig.getTxPoolMaxSize();
  }

  @Override
  public int size() {
    return pendingTransactions.size();
  }

  @Override
  public boolean containsTransaction(final Transaction transaction) {
    return pendingTransactions.containsKey(transaction.getHash());
  }

  @Override
  public Optional<Transaction> getTransactionByHash(final Hash transactionHash) {
    return Optional.ofNullable(pendingTransactions.get(transactionHash))
        .map(PendingTransaction::getTransaction);
  }

  @Override
  public Collection<PendingTransaction> getPendingTransactions() {
    return new ArrayList<>(pendingTransactions.values());
  }

  @Override
  public SenderPendingTransactionsData getPendingTransactionsFor(final Address sender) {
    final SenderState senderState = senderStates.get(sender);
    if (senderState == null) {
      return SenderPendingTransactionsData.empty(sender);
    }
    synchronized (senderState) {
      return new SenderPendingTransactionsData(
          sender, senderState.nextReadyNonce, senderTransactions(senderState));
    }
  }

  @Override
  public Map<Address, SenderPendingTransactionsData> getPendingTransactionsBySender() {
    final Map<Address, SenderPendingTransactionsData> result = new HashMap<>();
    senderStates.forEach((sender, unused) -> result.put(sender, getPendingTransactionsFor(sender)));
    return result;
  }

  /** Must be called while holding the sender state monitor. */
  private List<PendingTransaction> senderTransactions(final SenderState senderState) {
    final List<PendingTransaction> txs = new ArrayList<>();
    senderState.readySeqByNonce.values().stream()
        .map(readyQueue::get)
        .filter(Objects::nonNull)
        .forEach(txs::add);
    txs.addAll(senderState.future.values());
    return txs;
  }

  @Override
  public long subscribePendingTransactions(final PendingTransactionAddedListener listener) {
    return addedListeners.subscribe(listener);
  }

  @Override
  public void unsubscribePendingTransactions(final long id) {
    addedListeners.unsubscribe(id);
  }

  @Override
  public long subscribeDroppedTransactions(final PendingTransactionDroppedListener listener) {
    return droppedListeners.subscribe(listener);
  }

  @Override
  public void unsubscribeDroppedTransactions(final long id) {
    droppedListeners.unsubscribe(id);
  }

  @Override
  public OptionalLong getNextNonceForSender(final Address sender) {
    final SenderState senderState = senderStates.get(sender);
    if (senderState == null) {
      return OptionalLong.empty();
    }
    synchronized (senderState) {
      if (senderState.readySeqByNonce.isEmpty()) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(senderState.nextReadyNonce);
    }
  }

  @Override
  public String toTraceLog() {
    return "FIFO pool: "
        + readyQueue.values().stream()
            .map(PendingTransaction::toTraceLog)
            .collect(Collectors.joining("; ", "ready { ", " }"));
  }

  @Override
  public String logStats() {
    final int total = pendingTransactions.size();
    final int ready = readyQueue.size();
    return "FIFO Pending " + total + ", ready " + ready + ", future " + (total - ready);
  }

  @Override
  public Status getStatus() {
    final int total = pendingTransactions.size();
    final int ready = readyQueue.size();
    return new Status(ready, Math.max(0, total - ready));
  }

  @Override
  public Optional<Transaction> restoreBlob(final Transaction transaction) {
    // blob transactions are not expected on free-gas permissioned networks
    return Optional.empty();
  }

  private void notifyTransactionAdded(final Transaction transaction) {
    addedListeners.forEach(listener -> listener.onTransactionAdded(transaction));
  }

  private void notifyTransactionDropped(
      final PendingTransaction pendingTransaction, final RemovalReason reason) {
    transactionRemovedCounter
        .labels(pendingTransaction.isReceivedFromLocalSource() ? "local" : "remote", "dropped")
        .inc();
    droppedListeners.forEach(
        listener -> listener.onTransactionDropped(pendingTransaction.getTransaction(), reason));
  }
}

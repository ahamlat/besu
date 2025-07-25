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
package org.hyperledger.besu.tests.acceptance.bftsoak;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.config.JsonUtil;
import org.hyperledger.besu.tests.acceptance.bft.BftAcceptanceTestParameterization;
import org.hyperledger.besu.tests.acceptance.bft.ParameterizedBftTestBase;
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode;
import org.hyperledger.besu.tests.web3j.generated.SimpleStorage;
import org.hyperledger.besu.tests.web3j.generated.SimpleStorageShanghai;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.tx.exceptions.ContractCallException;

public class BftMiningSoakTest extends ParameterizedBftTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(BftMiningSoakTest.class);

  private final int NUM_STEPS = 5;

  private final int MIN_TEST_TIME_MINS = 60;

  private static final long ONE_MINUTE = Duration.of(1, ChronoUnit.MINUTES).toMillis();

  private static final long THREE_MINUTES = Duration.of(3, ChronoUnit.MINUTES).toMillis();

  private static final long TEN_SECONDS = Duration.of(10, ChronoUnit.SECONDS).toMillis();

  static int getTestDurationMins() {
    // Use a default soak time of 60 mins
    return Integer.getInteger("acctests.soakTimeMins", 60);
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("factoryFunctions")
  public void shouldBeStableDuringLongTest(
      final String testName, final BftAcceptanceTestParameterization nodeFactory) throws Exception {
    setUp(testName, nodeFactory);

    // There is a minimum amount of time the test can be run for, due to hard coded delays
    // in between certain steps. There should be no upper-limit to how long the test is run for
    assertThat(getTestDurationMins()).isGreaterThanOrEqualTo(MIN_TEST_TIME_MINS);

    // Create a mix of Bonsai and Forest DB nodes
    final BesuNode minerNode1 = nodeFactory.createBonsaiNodeFixedPort(besu, "miner1");
    final BesuNode minerNode2 = nodeFactory.createBonsaiArchiveNodeFixedPort(besu, "miner2");
    final BesuNode minerNode3 = nodeFactory.createBonsaiNodeFixedPort(besu, "miner3");
    final BesuNode minerNode4 = nodeFactory.createForestNodeFixedPort(besu, "miner4");

    // Each step should be given a minimum of 3 minutes to complete successfully. If the time
    // give to run the soak test results in a time-per-step lower than this then the time
    // needs to be increased.
    assertThat(getTestDurationMins() / NUM_STEPS).isGreaterThanOrEqualTo(3);

    cluster.start(minerNode1, minerNode2, minerNode3, minerNode4);

    cluster.verify(blockchain.reachesHeight(minerNode1, 1, 85));

    // Setup
    //    Deploy a contract that we'll invoke periodically to ensure state
    //    is correct during the test, especially after stopping nodes and
    //    applying new forks.
    SimpleStorage simpleStorageContract =
        minerNode1.execute(contractTransactions.createSmartContract(SimpleStorage.class));

    // Create another instance of the contract referencing the same contract address but on the
    // archive node. This contract instance should be able to query state from the beginning of
    // the test
    SimpleStorage simpleStorageArchive =
        minerNode2.execute(contractTransactions.createSmartContract(SimpleStorage.class));
    simpleStorageArchive.setContractAddress(simpleStorageContract.getContractAddress());

    // Check the contract address is as expected for this sender & nonce
    contractVerifier
        .validTransactionReceipt("0x42699a7612a82f1d9c36148af9c77354759b210b")
        .verify(simpleStorageContract);

    // Before upgrading to newer forks, try creating a shanghai-evm contract and check that
    // the transaction fails
    try {
      minerNode1.execute(contractTransactions.createSmartContract(SimpleStorageShanghai.class));
      Assertions.fail("Shanghai transaction should not be executed on a pre-shanghai chain");
    } catch (RuntimeException e) {
      assertThat(e.getMessage())
          .contains(
              "Revert reason: 'Transaction processing could not be completed due to an exception (Invalid opcode: 0x5f)'");
    }

    // Should initially be set to 0
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(0));

    // Set to something new
    simpleStorageContract.set(BigInteger.valueOf(101)).send();

    // Save this block height to check on the archive node at the end of the test
    BigInteger archiveChainHeight = minerNode1.execute(ethTransactions.blockNumber());

    // Check the state of the contract has updated correctly. We'll set & get this several times
    // during the test
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(101));

    // Step 1
    // Run for the configured time period, periodically checking that
    // the chain is progressing as expected
    BigInteger chainHeight = minerNode1.execute(ethTransactions.blockNumber());
    assertThat(chainHeight.compareTo(BigInteger.ZERO)).isGreaterThanOrEqualTo(1);
    BigInteger lastChainHeight = chainHeight;

    Instant startTime = Instant.now();
    Instant nextStepEndTime = startTime.plus(getTestDurationMins() / NUM_STEPS, ChronoUnit.MINUTES);

    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());

      // With 1-second block period chain height should have moved on by at least 50 blocks
      assertThat(chainHeight.compareTo(lastChainHeight.add(BigInteger.valueOf(50))))
          .isGreaterThanOrEqualTo(1);
      lastChainHeight = chainHeight;
    }
    Instant previousStepEndTime = Instant.now();

    // Step 2
    // Stop one of the nodes, check that the chain continues mining
    // blocks
    LOG.info("Stopping node 4 to check the chain continues mining (albeit more slowly)");
    stopNode(minerNode4);

    nextStepEndTime =
        previousStepEndTime.plus(getTestDurationMins() / NUM_STEPS, ChronoUnit.MINUTES);

    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());

      // Chain height should have moved on by at least 5 blocks
      assertThat(chainHeight.compareTo(lastChainHeight.add(BigInteger.valueOf(20))))
          .isGreaterThanOrEqualTo(1);
      lastChainHeight = chainHeight;
    }
    previousStepEndTime = Instant.now();

    // Step 3
    // Stop another one of the nodes, check that the chain now stops
    // mining blocks
    LOG.info("Stopping node 3 to check that the chain stalls");
    stopNode(minerNode3);

    chainHeight = minerNode1.execute(ethTransactions.blockNumber());
    lastChainHeight = chainHeight;

    // Leave the chain stalled for 1 minute. Check no new blocks are mined. Then
    // resume the other validators.
    nextStepEndTime = previousStepEndTime.plus(1, ChronoUnit.MINUTES);
    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());

      // Chain height should not have moved on
      assertThat(chainHeight.equals(lastChainHeight)).isTrue();
    }

    // Step 4
    // Restart both of the stopped nodes. Check that the chain resumes
    // mining blocks
    LOG.info("Starting node 3 and node 4 to ensure the chain resumes mining new blocks");
    startNode(minerNode3);
    startNode(minerNode4);

    previousStepEndTime = Instant.now();

    // This step gives the stalled chain time to re-sync and agree on the next BFT round
    chainHeight = minerNode1.execute(ethTransactions.blockNumber());
    nextStepEndTime =
        previousStepEndTime.plus((getTestDurationMins() / NUM_STEPS), ChronoUnit.MINUTES);
    lastChainHeight = chainHeight;

    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());
      lastChainHeight = chainHeight;
    }
    previousStepEndTime = Instant.now();

    // By this loop it should be producing blocks again
    nextStepEndTime =
        previousStepEndTime.plus(getTestDurationMins() / NUM_STEPS, ChronoUnit.MINUTES);

    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());

      // Chain height should have moved on by at least 1 block
      assertThat(chainHeight.compareTo(lastChainHeight.add(BigInteger.valueOf(1))))
          .isGreaterThanOrEqualTo(1);
      lastChainHeight = chainHeight;
    }

    LOG.info("Updating the value in the smart contract from 101 to 201");
    // Update our smart contract before upgrading from berlin to london
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(101));
    simpleStorageContract.set(BigInteger.valueOf(201)).send();
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(201));

    LOG.info(
        "Upgrading the entire chain to the London fork one node at a time. The genesis for each node will be updated to londonBlock = "
            + lastChainHeight.intValue()
            + 120);
    // Upgrade the chain from berlin to london in 120 blocks time
    int londonBlockNumber = lastChainHeight.intValue() + 120;
    upgradeToLondon(minerNode1, minerNode2, minerNode3, minerNode4, londonBlockNumber);

    minerNode4.verify(blockchain.minimumHeight(londonBlockNumber, 180));

    previousStepEndTime = Instant.now();

    chainHeight = minerNode1.execute(ethTransactions.blockNumber());
    nextStepEndTime =
        previousStepEndTime.plus(getTestDurationMins() / NUM_STEPS, ChronoUnit.MINUTES);
    lastChainHeight = chainHeight;

    // Allow the chain to restart mining blocks
    Thread.sleep(THREE_MINUTES);

    while (System.currentTimeMillis() < nextStepEndTime.toEpochMilli()) {
      Thread.sleep(ONE_MINUTE);
      chainHeight = minerNode1.execute(ethTransactions.blockNumber());

      // Chain height should have moved on by at least 50 blocks
      assertThat(chainHeight.compareTo(lastChainHeight.add(BigInteger.valueOf(50))))
          .isGreaterThanOrEqualTo(1);
      lastChainHeight = chainHeight;
    }

    LOG.info(
        "Chain has successfully upgraded to the London fork. Checking the contract state is correct");
    // Check that the state of our smart contract is still correct
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(201));

    // Update it once more to check new transactions are mined OK
    simpleStorageContract.set(BigInteger.valueOf(301)).send();
    assertThat(simpleStorageContract.get().send()).isEqualTo(BigInteger.valueOf(301));

    // Upgrade the chain to shanghai in 120 seconds. Then try to deploy a shanghai contract
    Instant shanghaiUpgradeStartTime = Instant.now();
    upgradeToShanghai(
        minerNode1,
        minerNode2,
        minerNode3,
        minerNode4,
        shanghaiUpgradeStartTime.getEpochSecond() + 120);

    cluster.verify(blockchain.reachesHeight(minerNode4, 1, 180));

    // Check if has been 120 seconds since the upgrade and wait otherwise
    while (Instant.now().getEpochSecond() < shanghaiUpgradeStartTime.getEpochSecond() + 130) {
      Thread.sleep(TEN_SECONDS);
    }

    LOG.info(
        "Deploying a smart contract that should only work if the chain is running on the shanghai fork");
    SimpleStorageShanghai simpleStorageContractShanghai =
        minerNode1.execute(contractTransactions.createSmartContract(SimpleStorageShanghai.class));

    // Check the contract address is as expected for this sender & nonce
    contractVerifier
        .validTransactionReceipt("0xfeae27388a65ee984f452f86effed42aabd438fd")
        .verify(simpleStorageContractShanghai);

    // Archive node test. Check the state of the contract when it was first updated in the test
    LOG.info(
        "Checking that the archive node shows us the original smart contract value if we set a historic block number");
    simpleStorageArchive.setDefaultBlockParameter(
        DefaultBlockParameter.valueOf(archiveChainHeight));
    assertThat(simpleStorageArchive.get().send()).isEqualTo(BigInteger.valueOf(101));

    try {
      simpleStorageContract.setDefaultBlockParameter(
          DefaultBlockParameter.valueOf(archiveChainHeight));
      // Should throw ContractCallException because a non-archive not can't satisfy this request
      simpleStorageContract.get().send();
      Assertions.fail("Request for historic state from non-archive node should have failed");
    } catch (ContractCallException e) {
      // Ignore
    }
  }

  private static void updateGenesisConfigToLondon(
      final BesuNode minerNode, final boolean zeroBaseFeeEnabled, final int blockNumber) {

    if (minerNode.getGenesisConfig().isPresent()) {
      final ObjectNode genesisConfigNode =
          JsonUtil.objectNodeFromString(minerNode.getGenesisConfig().get());
      final ObjectNode config = (ObjectNode) genesisConfigNode.get("config");
      config.put("londonBlock", blockNumber);
      config.put("zeroBaseFee", zeroBaseFeeEnabled);
      minerNode.setGenesisConfig(genesisConfigNode.toString());
    }
  }

  private static void updateGenesisConfigToShanghai(
      final BesuNode minerNode, final long blockTimestamp) {

    if (minerNode.getGenesisConfig().isPresent()) {
      final ObjectNode genesisConfigNode =
          JsonUtil.objectNodeFromString(minerNode.getGenesisConfig().get());
      final ObjectNode config = (ObjectNode) genesisConfigNode.get("config");
      config.put("shanghaiTime", blockTimestamp);
      minerNode.setGenesisConfig(genesisConfigNode.toString());
    }
  }

  private void upgradeToLondon(
      final BesuNode minerNode1,
      final BesuNode minerNode2,
      final BesuNode minerNode3,
      final BesuNode minerNode4,
      final int londonBlockNumber)
      throws InterruptedException {
    // Node 1

    LOG.info("Upgrading node 1 to london fork");
    stopNode(minerNode1);
    updateGenesisConfigToLondon(minerNode1, true, londonBlockNumber);
    startNode(minerNode1);

    // Node 2
    LOG.info("Upgrading node 2 to london fork");
    stopNode(minerNode2);
    updateGenesisConfigToLondon(minerNode2, true, londonBlockNumber);
    startNode(minerNode2);

    // Node 3
    LOG.info("Upgrading node 3 to london fork");
    stopNode(minerNode3);
    updateGenesisConfigToLondon(minerNode3, true, londonBlockNumber);
    startNode(minerNode3);

    // Node 4
    LOG.info("Upgrading node 4 to london fork");
    stopNode(minerNode4);
    updateGenesisConfigToLondon(minerNode4, true, londonBlockNumber);
    startNode(minerNode4);
  }

  private void upgradeToShanghai(
      final BesuNode minerNode1,
      final BesuNode minerNode2,
      final BesuNode minerNode3,
      final BesuNode minerNode4,
      final long shanghaiTime)
      throws InterruptedException {
    // Node 1
    LOG.info("Upgrading node 1 to shanghai fork");
    stopNode(minerNode1);
    updateGenesisConfigToShanghai(minerNode1, shanghaiTime);
    startNode(minerNode1);

    // Node 2
    LOG.info("Upgrading node 2 to shanghai fork");
    stopNode(minerNode2);
    updateGenesisConfigToShanghai(minerNode2, shanghaiTime);
    startNode(minerNode2);

    // Node 3
    LOG.info("Upgrading node 3 to shanghai fork");
    stopNode(minerNode3);
    updateGenesisConfigToShanghai(minerNode3, shanghaiTime);
    startNode(minerNode3);

    // Node 4
    LOG.info("Upgrading node 4 to shanghai fork");
    stopNode(minerNode4);
    updateGenesisConfigToShanghai(minerNode4, shanghaiTime);
    startNode(minerNode4);
  }

  // Start a node with a delay before returning to give it time to start
  private void startNode(final BesuNode node) throws InterruptedException {
    cluster.startNode(node);
    Thread.sleep(TEN_SECONDS);
  }

  // Stop a node with a delay before returning to give it time to stop
  private void stopNode(final BesuNode node) throws InterruptedException {
    cluster.stopNode(node);
    Thread.sleep(TEN_SECONDS);
  }

  @AfterEach
  @Override
  public void tearDownAcceptanceTestBase() {
    cluster.stop();
    super.tearDownAcceptanceTestBase();
  }
}

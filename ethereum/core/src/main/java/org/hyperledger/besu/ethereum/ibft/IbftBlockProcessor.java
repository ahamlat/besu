package org.hyperledger.besu.ethereum.ibft;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.GoQuorumPrivacyParameters;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;

import java.util.Optional;

public class IbftBlockProcessor extends MainnetBlockProcessor {

    public IbftBlockProcessor(
        final MainnetTransactionProcessor transactionProcessor,
        final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory,
        final Wei blockReward,
        final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
        final boolean skipZeroBlockRewards,
        final Optional<GoQuorumPrivacyParameters> goQuorumPrivacyParameters) {
            super(
                    transactionProcessor,
                    transactionReceiptFactory,
                    blockReward,
                    miningBeneficiaryCalculator,
                    skipZeroBlockRewards,
                    Optional.empty());

            ;
    }

}

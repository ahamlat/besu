package org.hyperledger.besu.ethereum.ibft;

import org.hyperledger.besu.ethereum.MainnetBlockValidator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BlockBodyValidator;
import org.hyperledger.besu.ethereum.mainnet.BlockHeaderValidator;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class IbftBlockValidator extends MainnetBlockValidator {

    private final ReceiptsCache receiptsCache;

    public IbftBlockValidator(
            final BlockHeaderValidator blockHeaderValidator,
            final BlockBodyValidator blockBodyValidator,
            final BlockProcessor blockProcessor,
            final BadBlockManager badBlockManager,
            final ReceiptsCache receiptsCache) {
        super(blockHeaderValidator, blockBodyValidator, blockProcessor, badBlockManager);
        this.receiptsCache = new ReceiptsCache();
        if (!(blockProcessor instanceof IbftBlockProcessor)) {
            throw new IllegalStateException(
                    "IbftBlockValidator requires an instance of IbftBlockProcessor");
        }
    }

    @Override
    public Result validateAndProcessBlock(
            final ProtocolContext context,
            final Block block,
            final HeaderValidationMode headerValidationMode,
            final HeaderValidationMode ommerValidationMode) {
        final BlockHeader header = block.getHeader();

        final MutableBlockchain blockchain = context.getBlockchain();
        final Optional<BlockHeader> maybeParentHeader =
                blockchain.getBlockHeader(header.getParentHash());
        if (maybeParentHeader.isEmpty()) {
            return handleAndReportFailure(
                    block, "Parent block with hash " + header.getParentHash() + " not present");
        }
        final BlockHeader parentHeader = maybeParentHeader.get();

        if (!blockHeaderValidator.validateHeader(header, parentHeader, context, headerValidationMode)) {
            return handleAndReportFailure(block, "Invalid block header");
        }

        Optional<MutableWorldState> maybeWorldState =
                context
                        .getWorldStateArchive()
                        .getMutable(block.getHeader().getStateRoot(), block.getHeader().getHash());
        if(maybeWorldState.isEmpty()){
            maybeWorldState =
                    context
                            .getWorldStateArchive()
                            .getMutable(parentHeader.getStateRoot(), parentHeader.getHash());
            if (maybeWorldState.isEmpty()) {
                return handleAndReportFailure(
                        block,
                        "Unable to process block because parent world state "
                                + parentHeader.getStateRoot()
                                + " is not available");
            }
        }

        final MutableWorldState worldState = maybeWorldState.get();

        BlockProcessor.Result result = null;
        if(!maybeWorldState.get().rootHash().equals(block.getHeader().getStateRoot()) || receiptsCache.getIfPresent(block.getHeader().getReceiptsRoot()) == null) {
            result = processBlock(context, worldState, block);

            if (result.isFailed()) {
                return handleAndReportFailure(block, "Error processing block");
            }
        }
        List<TransactionReceipt> receipts = receiptsCache.getIfPresent(block.getHeader().getReceiptsRoot());
        if (receipts == null) {
            receipts = result.getReceipts();
            receiptsCache.put(block.getHeader().getReceiptsRoot(), receipts);
        }

        if (!blockBodyValidator.validateBody(
                context, block, receipts, worldState.rootHash(), ommerValidationMode)) {
            return handleAndReportFailure(block, "Block body not valid");
        }

        if (result != null && !result.getPrivateReceipts().isEmpty()) {
            // replace the public receipts for marker transactions with the private receipts if we are in
            // goQuorumCompatibilityMode. That can be done now because we have validated the block.
            final List<TransactionReceipt> privateTransactionReceipts = result.getPrivateReceipts();
            final ArrayList<TransactionReceipt> resultingList = new ArrayList<>(receipts.size());
            for (int i = 0; i < receipts.size(); i++) {
                if (privateTransactionReceipts.get(i) != null) {
                    resultingList.add(privateTransactionReceipts.get(i));
                } else {
                    resultingList.add(receipts.get(i));
                }
            }
            receipts = Collections.unmodifiableList(resultingList);
        }

        return new Result(new BlockProcessingOutputs(worldState, receipts));
    }
}

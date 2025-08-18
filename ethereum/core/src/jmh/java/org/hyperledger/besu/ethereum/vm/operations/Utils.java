package org.hyperledger.besu.ethereum.vm.operations;

import org.apache.tuweni.bytes.Bytes;

import java.util.Random;

public class Utils {

    public static Bytes generateBytes(final int nbBytes) {
        final Random random = new Random();
        final byte[] byteArray = new byte[nbBytes];
        random.nextBytes(byteArray);
        return Bytes.wrap(byteArray);
    }
}

package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.collections.trie.BytesTrieSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class BytesTrieSetVsHashSetBenchmark {

    @Param({"10000", "100000"})
    private int setSize;

    @Param({"0.5", "1.0"}) // 0.5 = 50% hit rate, 1.0 = 100% hit rate
    private double hitRate;

    private Set<Address> hashSet;
    private BytesTrieSet<Address> trieSet;

    private Address[] presentAddresses;
    private Address[] queryAddresses; // Mix of present and absent based on hitRate
    private Address[] newAddresses; // For add operations

    private static final int QUERY_SIZE = 1000;
    private static final long SEED = 42L;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(SEED);

        // Initialize sets
        hashSet = new HashSet<>(setSize);
        trieSet = new BytesTrieSet<>(20); // Address is 20 bytes

        // Generate addresses that will be in the set
        presentAddresses = new Address[setSize];
        for (int i = 0; i < setSize; i++) {
            presentAddresses[i] = generateRandomAddress(random);
            hashSet.add(presentAddresses[i]);
            trieSet.add(presentAddresses[i]);
        }

        // Generate query addresses based on hit rate
        queryAddresses = new Address[QUERY_SIZE];
        int hitCount = (int) (QUERY_SIZE * hitRate);
        for (int i = 0; i < hitCount; i++) {
            // Use existing addresses for hits
            queryAddresses[i] = presentAddresses[random.nextInt(setSize)];
        }
        for (int i = hitCount; i < QUERY_SIZE; i++) {
            // Generate new addresses for misses
            queryAddresses[i] = generateRandomAddress(random);
        }

        // Shuffle query addresses
        shuffleArray(queryAddresses, random);

        // Generate new addresses for add operations
        newAddresses = new Address[QUERY_SIZE];
        for (int i = 0; i < QUERY_SIZE; i++) {
            newAddresses[i] = generateRandomAddress(random);
        }
    }

    // ==================== CONTAINS BENCHMARKS ====================

    @Benchmark
    public void containsHashSet(final Blackhole blackhole) {
        for (Address address : queryAddresses) {
            blackhole.consume(hashSet.contains(address));
        }
    }

    @Benchmark
    public void containsTrieSet(final Blackhole blackhole) {
        for (Address address : queryAddresses) {
            blackhole.consume(trieSet.contains(address));
        }
    }

    @Benchmark
    @OperationsPerInvocation(1)
    public boolean containsSingleHashSet() {
        return hashSet.contains(queryAddresses[0]);
    }

    @Benchmark
    @OperationsPerInvocation(1)
    public boolean containsSingleTrieSet() {
        return trieSet.contains(queryAddresses[0]);
    }

    // ==================== ADD BENCHMARKS ====================

    @Benchmark
    @OperationsPerInvocation(1)
    public boolean addHashSet(final AddState state) {
        boolean result = state.hashSetCopy.add(state.newAddresses[state.index]);
        state.index = (state.index + 1) % QUERY_SIZE;
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(1)
    public boolean addTrieSet(final AddState state) {
        boolean result = state.trieSetCopy.add(state.newAddresses[state.index]);
        state.index = (state.index + 1) % QUERY_SIZE;
        return result;
    }

    @State(Scope.Thread)
    public static class AddState {
        HashSet<Address> hashSetCopy;
        BytesTrieSet<Address> trieSetCopy;
        Address[] newAddresses;
        int index = 0;

        @Setup(Level.Invocation)
        public void setup(final BytesTrieSetVsHashSetBenchmark parent) {
            // Create fresh copies for each invocation to avoid set growth effects
            hashSetCopy = new HashSet<>(parent.hashSet);
            trieSetCopy = new BytesTrieSet<>(20);
            for (Address addr : parent.presentAddresses) {
                trieSetCopy.add(addr);
            }
            newAddresses = parent.newAddresses;
        }
    }

    @Benchmark
    public void iterateHashSet(final Blackhole blackhole) {
        for (Address address : hashSet) {
            blackhole.consume(address);
        }
    }

    @Benchmark
    public void iterateTrieSet(final Blackhole blackhole) {
        for (Address address : trieSet) {
            blackhole.consume(address);
        }
    }

    private Address generateRandomAddress(final Random random) {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }

    private void shuffleArray(final Address[] array, final Random random) {
        for (int i = array.length - 1; i > 0; i--) {
            int index = random.nextInt(i + 1);
            Address temp = array[index];
            array[index] = array[i];
            array[i] = temp;
        }
    }
}
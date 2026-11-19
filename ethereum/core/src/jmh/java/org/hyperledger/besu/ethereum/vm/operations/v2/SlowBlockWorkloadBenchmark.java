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
package org.hyperledger.besu.ethereum.vm.operations.v2;

import static org.mockito.Mockito.mock;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.code.BonsaiCodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.provider.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.EvmConfiguration.WorldUpdaterMode;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Reproduces the hot opcode and call-tree profile of the slow Mainnet blocks from Nov 18, 2026
 * (Slots 15681725-15681763, e.g. Block #26,441,964 / Slot 15681760 where a single mega-tx executes
 * ~14.2k SLOADs, ~1.85k SSTOREs, ~2.6k CALL/STATICCALL/DELEGATECALLs, ~6.4k 64-byte KECCAK256s, and
 * ~735 LOGs).
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SlowBlockWorkloadBenchmark {

  private static final Address SENDER =
      Address.fromHexString("0x000000000000000000000000000000000000aaaa");
  private static final Address CONTROLLER =
      Address.fromHexString("0x000000000000000000000000000000000000c001");
  private static final Address READ_CALLEE =
      Address.fromHexString("0x000000000000000000000000000000000000c002");
  private static final Address WRITE_CALLEE =
      Address.fromHexString("0x000000000000000000000000000000000000c003");
  private static final Address EMPTY_CALLEE =
      Address.fromHexString("0x000000000000000000000000000000000000c004");
  private static final Address SLOAD_CALLEE =
      Address.fromHexString("0x000000000000000000000000000000000000c005");
  private static final Address SSTORE_CALLEE =
      Address.fromHexString("0x000000000000000000000000000000000000c006");

  private BonsaiWorldState worldState;
  private EVMExecutor megaTxExecutor;
  private EVMExecutor sloadExecutor;
  private EVMExecutor keccakExecutor;
  private EVMExecutor callsExecutor;
  private EVMExecutor sstoreExecutor;

  @Setup(Level.Trial)
  public void setUp() {
    final EvmConfiguration evmConfig =
        new EvmConfiguration(32_000L, WorldUpdaterMode.STACKED, true, true);
    final BonsaiWorldStateProvider archive =
        new BonsaiWorldStateProvider(
            (BonsaiWorldStateKeyValueStorage)
                new InMemoryKeyValueStorageProvider()
                    .createWorldStateStorage(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG),
            mock(Blockchain.class),
            DataStorageConfiguration.DEFAULT_BONSAI_CONFIG.getExtraStorageConfiguration(),
            new BonsaiCachedMerkleTrieLoader(new NoOpMetricsSystem()),
            null,
            evmConfig,
            new BonsaiCodeCache(128L * 1024L * 1024L));
    worldState = (BonsaiWorldState) archive.getWorldState();

    final WorldUpdater setup = worldState.updater();
    setup.createAccount(SENDER, 1, Wei.of(1_000_000_000L));
    final MutableAccount ctrl = setup.createAccount(CONTROLLER, 1, Wei.ZERO);
    final MutableAccount readAcc = setup.createAccount(READ_CALLEE, 1, Wei.ZERO);
    final MutableAccount writeAcc = setup.createAccount(WRITE_CALLEE, 1, Wei.ZERO);
    final MutableAccount emptyAcc = setup.createAccount(EMPTY_CALLEE, 1, Wei.ZERO);
    final MutableAccount sloadAcc = setup.createAccount(SLOAD_CALLEE, 1, Wei.ZERO);
    final MutableAccount sstoreAcc = setup.createAccount(SSTORE_CALLEE, 1, Wei.ZERO);

    // Pre-populate 256 non-zero slots on each contract
    for (int i = 0; i < 256; i++) {
      final UInt256 key = UInt256.valueOf(i);
      final UInt256 val = UInt256.valueOf(0x1000L + i);
      ctrl.setStorageValue(key, val);
      readAcc.setStorageValue(key, val);
      writeAcc.setStorageValue(key, val);
      sloadAcc.setStorageValue(key, val);
      sstoreAcc.setStorageValue(key, val);
    }

    readAcc.setCode(buildReadCalleeCode());
    writeAcc.setCode(buildWriteCalleeCode());
    emptyAcc.setCode(Bytes.of(0x00)); // STOP
    sloadAcc.setCode(buildSloadLoopCode(14_200));
    sstoreAcc.setCode(buildSstoreLoopCode(1_850));

    setup.commit();
    worldState.persist(null);

    final EvmSpec spec = EvmSpec.evmSpec(EvmSpecVersion.OSAKA, BigInteger.ONE, evmConfig);

    megaTxExecutor = createExecutor(spec, buildMegaTxCode(750));
    sloadExecutor = createExecutor(spec, buildDepth1Wrapper(SLOAD_CALLEE));
    keccakExecutor = createExecutor(spec, buildKeccakLoopCode(6_400));
    callsExecutor = createExecutor(spec, buildCallsLoopCode(750));
    sstoreExecutor = createExecutor(spec, buildDepth1Wrapper(SSTORE_CALLEE));

    // Smoke-test all 5 executors once
    worldState.updater().reset();
    megaTxExecutor.worldUpdater(worldState.updater()).execute();
    worldState.updater().reset();
    sloadExecutor.worldUpdater(worldState.updater()).execute();
    worldState.updater().reset();
    keccakExecutor.worldUpdater(worldState.updater()).execute();
    worldState.updater().reset();
    callsExecutor.worldUpdater(worldState.updater()).execute();
    worldState.updater().reset();
    sstoreExecutor.worldUpdater(worldState.updater()).execute();
  }

  private static EVMExecutor createExecutor(final EvmSpec spec, final Bytes code) {
    return new EVMExecutor(spec)
        .code(code)
        .sender(SENDER)
        .receiver(CONTROLLER)
        .gas(1_000_000_000L)
        .tracer(OperationTracer.NO_TRACING);
  }

  @Setup(Level.Invocation)
  public void resetWorld() {
    worldState.updater().reset();
  }

  @Benchmark
  public Bytes megaTxComposite() {
    final WorldUpdater txUpdater = worldState.updater().updater();
    final Bytes out = megaTxExecutor.worldUpdater(txUpdater).execute();
    txUpdater.commit();
    return out;
  }

  @Benchmark
  public Bytes sloadWarm14k() {
    final WorldUpdater txUpdater = worldState.updater().updater();
    final Bytes out = sloadExecutor.worldUpdater(txUpdater).execute();
    txUpdater.commit();
    return out;
  }

  @Benchmark
  public Bytes keccak64B_6400() {
    final WorldUpdater txUpdater = worldState.updater().updater();
    final Bytes out = keccakExecutor.worldUpdater(txUpdater).execute();
    txUpdater.commit();
    return out;
  }

  @Benchmark
  public Bytes calls3000() {
    final WorldUpdater txUpdater = worldState.updater().updater();
    final Bytes out = callsExecutor.worldUpdater(txUpdater).execute();
    txUpdater.commit();
    return out;
  }

  @Benchmark
  public Bytes sstoreWarm1850() {
    final WorldUpdater txUpdater = worldState.updater().updater();
    final Bytes out = sstoreExecutor.worldUpdater(txUpdater).execute();
    txUpdater.commit();
    return out;
  }

  // Wrapper that does a CALL into target (depth 1 -> depth 2), matching mainnet proxy/router depth
  private static Bytes buildDepth1Wrapper(final Address target) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    // CALL(gas, target, 0, 0, 0, 0, 0)
    emitCall(c, 0xF1, target, true);
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  // READ_CALLEE: SLOAD(slot), SLOAD(slot+1), MSTORE(0, sum), RETURN(0, 32)
  private static Bytes buildReadCalleeCode() {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    // slot = calldataload(0) & 0xff
    c.write(0x5F); // PUSH0
    c.write(0x35); // CALLDATALOAD
    c.writeBytes(new byte[] {0x60, (byte) 0xFF, 0x16}); // PUSH1 0xff, AND
    c.write(0x80); // DUP1
    c.write(0x54); // SLOAD
    c.write(0x90); // SWAP1
    c.writeBytes(new byte[] {0x60, 0x01, 0x01, 0x60, (byte) 0xFF, 0x16}); // +1 & 0xff
    c.write(0x54); // SLOAD
    c.write(0x01); // ADD
    c.write(0x5F); // PUSH0
    c.write(0x52); // MSTORE
    c.writeBytes(new byte[] {0x60, 0x20, 0x5F, (byte) 0xF3}); // PUSH1 32, PUSH0, RETURN
    return Bytes.wrap(c.toByteArray());
  }

  // WRITE_CALLEE: 3 SLOADs + 1 KECCAK256(0, 64) + 2 SSTOREs + 1 LOG2(0, 32, t1, t2) + RETURN(0, 32)
  private static Bytes buildWriteCalleeCode() {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    // slot = calldataload(0) & 0xff
    c.write(0x5F); // PUSH0
    c.write(0x35); // CALLDATALOAD
    c.writeBytes(new byte[] {0x60, (byte) 0xFF, 0x16}); // PUSH1 0xff, AND -> [slot]
    // 3 SLOADs
    c.write(0x80); // DUP1
    c.write(0x54); // SLOAD -> [val0, slot]
    c.write(0x81); // DUP2
    c.writeBytes(new byte[] {0x60, 0x01, 0x01, 0x60, (byte) 0xFF, 0x16, 0x54}); // SLOAD(slot+1)
    c.write(0x01); // ADD
    c.write(0x81); // DUP2
    c.writeBytes(new byte[] {0x60, 0x02, 0x01, 0x60, (byte) 0xFF, 0x16, 0x54}); // SLOAD(slot+2)
    c.write(0x01); // ADD -> [sum, slot]
    // Store sum at mem[0], slot at mem[32], KECCAK256(0, 64)
    c.write(0x80); // DUP1
    c.write(0x5F); // PUSH0
    c.write(0x52); // MSTORE(0, sum)
    c.write(0x81); // DUP2
    c.writeBytes(new byte[] {0x60, 0x20, 0x52}); // MSTORE(32, slot)
    c.writeBytes(new byte[] {0x60, 0x40, 0x5F, 0x20}); // KECCAK256(0, 64) -> [h, sum, slot]
    // 2 SSTOREs (non-zero -> non-zero)
    c.writeBytes(new byte[] {0x60, 0x01, 0x17}); // OR 1 (guarantee non-zero)
    c.write(0x82); // DUP3 (slot)
    c.write(0x55); // SSTORE(slot, h|1) -> [sum, slot]
    c.writeBytes(new byte[] {0x60, 0x01, 0x01}); // sum+1
    c.write(0x81); // DUP2
    c.writeBytes(new byte[] {0x60, 0x01, 0x01, 0x60, (byte) 0xFF, 0x16}); // (slot+1)&0xff
    c.write(0x55); // SSTORE -> [slot]
    // LOG2(0, 32, slot, slot)
    c.write(0x80); // topic2
    c.write(0x80); // topic1
    c.writeBytes(new byte[] {0x60, 0x20, 0x5F, (byte) 0xA2}); // LOG2
    c.write(0x50); // POP slot
    c.writeBytes(new byte[] {0x60, 0x20, 0x5F, (byte) 0xF3}); // RETURN(0, 32)
    return Bytes.wrap(c.toByteArray());
  }

  private static Bytes buildSloadLoopCode(final int count) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    // Push counter = count
    c.writeBytes(new byte[] {0x61, (byte) (count >>> 8), (byte) count});
    final int loopStart = c.size();
    c.write(0x5B); // JUMPDEST -> [i]
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x60, (byte) 0xFF, 0x16}); // i & 0xff
    c.write(0x54); // SLOAD
    c.write(0x50); // POP
    c.writeBytes(new byte[] {0x60, 0x01, (byte) 0x90, 0x03}); // i = i - 1
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x61, (byte) (loopStart >>> 8), (byte) loopStart, 0x57}); // JUMPI
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  private static Bytes buildSstoreLoopCode(final int count) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    c.writeBytes(new byte[] {0x61, (byte) (count >>> 8), (byte) count});
    final int loopStart = c.size();
    c.write(0x5B); // JUMPDEST -> [i]
    c.write(0x80); // DUP1 (value = i, always >= 1)
    c.write(0x81); // DUP2
    c.writeBytes(new byte[] {0x60, (byte) 0xFF, 0x16}); // key = i & 0xff
    c.write(0x55); // SSTORE(key, i)
    c.writeBytes(new byte[] {0x60, 0x01, (byte) 0x90, 0x03}); // i - 1
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x61, (byte) (loopStart >>> 8), (byte) loopStart, 0x57}); // JUMPI
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  private static Bytes buildKeccakLoopCode(final int count) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    // Initialize 64 bytes of memory
    c.writeBytes(new byte[] {0x60, 0x42, 0x5F, 0x52, 0x60, 0x24, 0x60, 0x20, 0x52});
    c.writeBytes(new byte[] {0x61, (byte) (count >>> 8), (byte) count});
    final int loopStart = c.size();
    c.write(0x5B); // JUMPDEST -> [i]
    c.write(0x80); // DUP1
    c.write(0x5F); // PUSH0
    c.write(0x52); // MSTORE(0, i)
    c.writeBytes(new byte[] {0x60, 0x40, 0x5F, 0x20}); // KECCAK256(0, 64)
    c.write(0x50); // POP
    c.writeBytes(new byte[] {0x60, 0x01, (byte) 0x90, 0x03}); // i - 1
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x61, (byte) (loopStart >>> 8), (byte) loopStart, 0x57}); // JUMPI
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  // 750 iterations * (2 STATICCALL + 1 0-value CALL + 1 DELEGATECALL) = 3,000 calls
  private static Bytes buildCallsLoopCode(final int iterations) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    c.writeBytes(new byte[] {0x61, (byte) (iterations >>> 8), (byte) iterations});
    final int loopStart = c.size();
    c.write(0x5B); // JUMPDEST -> [i]
    emitCall(c, 0xFA, EMPTY_CALLEE, false); // STATICCALL
    c.write(0x50);
    emitCall(c, 0xFA, EMPTY_CALLEE, false); // STATICCALL
    c.write(0x50);
    emitCall(c, 0xF1, EMPTY_CALLEE, true); // 0-value CALL
    c.write(0x50);
    emitCall(c, 0xF4, EMPTY_CALLEE, false); // DELEGATECALL
    c.write(0x50);
    c.writeBytes(new byte[] {0x60, 0x01, (byte) 0x90, 0x03}); // i - 1
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x61, (byte) (loopStart >>> 8), (byte) loopStart, 0x57}); // JUMPI
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  // 750 iterations matching tx[0] of Block #26,441,964:
  // ~14,250 SLOAD, ~1,500 SSTORE, ~3,000 CALL/STATICCALL/DELEGATECALL, ~6,000 KECCAK256, ~750 LOG2
  private static Bytes buildMegaTxCode(final int iterations) {
    final ByteArrayOutputStream c = new ByteArrayOutputStream();
    c.writeBytes(new byte[] {0x61, (byte) (iterations >>> 8), (byte) iterations});
    final int loopStart = c.size();
    c.write(0x5B); // JUMPDEST -> [i]
    // Put i in mem[0] (calldata for callees + keccak input)
    c.write(0x80); // DUP1
    c.write(0x5F); // PUSH0
    c.write(0x52); // MSTORE(0, i)
    // 2x STATICCALL to READ_CALLEE (4 SLOADs)
    emitCallWithIO(c, 0xFA, READ_CALLEE, false, 0, 32, 64, 32);
    c.write(0x50);
    emitCallWithIO(c, 0xFA, READ_CALLEE, false, 0, 32, 64, 32);
    c.write(0x50);
    // 1x 0-value CALL to WRITE_CALLEE (3 SLOADs, 1 KECCAK, 2 SSTOREs, 1 LOG2)
    emitCallWithIO(c, 0xF1, WRITE_CALLEE, true, 0, 32, 64, 32);
    c.write(0x50);
    // 1x DELEGATECALL to READ_CALLEE (2 SLOADs)
    emitCallWithIO(c, 0xF4, READ_CALLEE, false, 0, 32, 64, 32);
    c.write(0x50);
    // 10 warm SLOADs in caller
    for (int k = 0; k < 10; k++) {
      c.write(0x80); // DUP1
      c.writeBytes(new byte[] {0x60, (byte) k, 0x01, 0x60, (byte) 0xFF, 0x16, 0x54, 0x50});
    }
    // 7x 64-byte KECCAK256 in caller
    for (int k = 0; k < 7; k++) {
      c.writeBytes(new byte[] {0x60, 0x40, 0x5F, 0x20, 0x50});
    }
    c.writeBytes(new byte[] {0x60, 0x01, (byte) 0x90, 0x03}); // i - 1
    c.write(0x80); // DUP1
    c.writeBytes(new byte[] {0x61, (byte) (loopStart >>> 8), (byte) loopStart, 0x57}); // JUMPI
    c.write(0x50); // POP
    c.write(0x00); // STOP
    return Bytes.wrap(c.toByteArray());
  }

  private static void emitCall(
      final ByteArrayOutputStream c,
      final int opcode,
      final Address target,
      final boolean hasValue) {
    emitCallWithIO(c, opcode, target, hasValue, 0, 0, 0, 0);
  }

  private static void emitCallWithIO(
      final ByteArrayOutputStream c,
      final int opcode,
      final Address target,
      final boolean hasValue,
      final int inOff,
      final int inLen,
      final int outOff,
      final int outLen) {
    // Stack order (bottom -> top): retLen, retOff, argsLen, argsOff, [value], addr, gas
    pushSmall(c, outLen);
    pushSmall(c, outOff);
    pushSmall(c, inLen);
    pushSmall(c, inOff);
    if (hasValue) {
      c.write(0x5F); // PUSH0 value
    }
    // PUSH20 target
    c.write(0x73);
    c.writeBytes(target.getBytes().toArrayUnsafe());
    c.write(0x5A); // GAS
    c.write(opcode);
  }

  private static void pushSmall(final ByteArrayOutputStream c, final int val) {
    if (val == 0) {
      c.write(0x5F); // PUSH0
    } else {
      c.write(0x60);
      c.write(val & 0xFF);
    }
  }
}

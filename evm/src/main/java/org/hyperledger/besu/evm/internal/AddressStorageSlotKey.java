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
package org.hyperledger.besu.evm.internal;

import org.hyperledger.besu.crypto.SecureRandomProvider;
import org.hyperledger.besu.datatypes.Address;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Key to use for lookups in Maps/Sets with an address and storage slot combined as a key.
 *
 * <p>The slot is fully attacker-controlled - e.g. it can be whatever a contract pushes to {@code
 * TSTORE} - so {@link #hashCode()} is a <em>seeded</em> hash, mixing in per-process random values
 * drawn once at class initialisation. Seed randomization is very important as it makes the hash
 * non-deterministic off chain so collisions can only be found out while executing EVM payloads
 * which costs gas.
 *
 * <p>Hashcode is based on multiply-shift universal hashing (Dietzfelbinger) which is efficient
 * enough and has good collision probability for hash tables ({@code <= 2^-32} probability of
 * collision). Even in the case of collisions Java HashMaps treeifies buckets, iff this class
 * implements {@link Comparable} directly, and search goes O(log n) instead of O(n) which limits
 * effects of collisions.
 */
public class AddressStorageSlotKey implements Comparable<AddressStorageSlotKey> {

  private static final VarHandle LONG_BE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
  private static final VarHandle LONG_VIEW =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  protected byte[] address;
  protected long u3;
  protected long u2;
  protected long u1;
  protected long u0;
  protected int cachedHash;

  /**
   * The AddressStorageSlotKey constructor.
   *
   * @param address the bytes from the address part of the key
   * @param slot the bytes from slot part of the key
   */
  public AddressStorageSlotKey(final Address address, final Bytes32 slot) {
    this.address = address.getBytes().toArrayUnsafe();
    final byte[] s = slot.toArrayUnsafe();
    this.u3 = (long) LONG_BE.get(s, 0);
    this.u2 = (long) LONG_BE.get(s, 8);
    this.u1 = (long) LONG_BE.get(s, 16);
    this.u0 = (long) LONG_BE.get(s, 24);
    this.cachedHash = computeHash(this.address, this.u3, this.u2, this.u1, this.u0);
  }

  /** Constructs a key directly from an address and the four big-endian limbs of a 256-bit slot. */
  public AddressStorageSlotKey(
      final Address address, final long u3, final long u2, final long u1, final long u0) {
    this.address = address.getBytes().toArrayUnsafe();
    this.u3 = u3;
    this.u2 = u2;
    this.u1 = u1;
    this.u0 = u0;
    this.cachedHash = computeHash(this.address, u3, u2, u1, u0);
  }

  /** Reusable single-threaded probe key for zero-allocation map lookups. */
  public static final class Probe extends AddressStorageSlotKey {
    public Probe() {
      super(Address.ZERO, 0L, 0L, 0L, 0L);
    }

    public Probe set(
        final Address address, final long u3, final long u2, final long u1, final long u0) {
      this.address = address.getBytes().toArrayUnsafe();
      this.u3 = u3;
      this.u2 = u2;
      this.u1 = u1;
      this.u0 = u0;
      this.cachedHash = computeHash(this.address, u3, u2, u1, u0);
      return this;
    }
  }

  private static final long SEED_0;
  private static final long SEED_1;
  private static final long SEED_2;
  private static final long SEED_3;
  private static final long SEED_4;
  private static final long SEED_5;
  private static final long SEED_6;

  static {
    final SecureRandom random = SecureRandomProvider.createSecureRandom();
    SEED_0 = random.nextLong() | 1L;
    SEED_1 = random.nextLong() | 1L;
    SEED_2 = random.nextLong() | 1L;
    SEED_3 = random.nextLong() | 1L;
    SEED_4 = random.nextLong() | 1L;
    SEED_5 = random.nextLong() | 1L;
    SEED_6 = random.nextLong() | 1L;
  }

  private static long word(final byte[] bytes, final int offset) {
    return (long) LONG_VIEW.get(bytes, offset);
  }

  static int computeHash(
      final byte[] address, final long u3, final long u2, final long u1, final long u0) {
    final long hash =
        u3 * SEED_0
            + u2 * SEED_1
            + u1 * SEED_2
            + u0 * SEED_3
            + word(address, 0) * SEED_4
            + word(address, 8) * SEED_5
            + word(address, 12) * SEED_6;
    return (int) (hash >>> 32);
  }

  @Override
  public int hashCode() {
    return cachedHash;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    } else if (obj instanceof AddressStorageSlotKey other) {
      return u0 == other.u0
          && u1 == other.u1
          && u2 == other.u2
          && u3 == other.u3
          && Arrays.equals(address, other.address);
    }
    return false;
  }

  @Override
  public int compareTo(final AddressStorageSlotKey other) {
    if (this == other) {
      return 0;
    }
    if (other == null) {
      return 1;
    }
    int compare = Arrays.compare(address, other.address);
    if (compare != 0) return compare;
    if (u3 != other.u3) return Long.compareUnsigned(u3, other.u3);
    if (u2 != other.u2) return Long.compareUnsigned(u2, other.u2);
    if (u1 != other.u1) return Long.compareUnsigned(u1, other.u1);
    return Long.compareUnsigned(u0, other.u0);
  }

  /**
   * Return the address part of this key.
   *
   * @return the address
   */
  public Address address() {
    return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(address));
  }

  /**
   * Return the slot part of this key.
   *
   * @return the slot
   */
  public Bytes32 slot() {
    final byte[] s = new byte[32];
    LONG_BE.set(s, 0, u3);
    LONG_BE.set(s, 8, u2);
    LONG_BE.set(s, 16, u1);
    LONG_BE.set(s, 24, u0);
    return Bytes32.wrap(s);
  }
}

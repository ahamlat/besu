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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import java.nio.ByteBuffer;
import java.util.Optional;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 * Reads values out of RocksDB into a reusable, thread-local direct {@link ByteBuffer} instead of
 * letting the RocksDB JNI layer allocate a fresh Java {@code byte[]} for every lookup (the
 * {@code createJavaByteArrayWithSizeCheck} / {@code NewByteArray} hot path).
 *
 * <p>The reusable value buffer is sized for contract code (EIP-170 caps deployed code at 24576
 * bytes). For values that do not fit, this falls back to the normal allocating {@code get} so
 * correctness is never compromised. This is intentionally scoped to the code-read path for now.
 */
final class RocksDBBufferedValueReader {

  private RocksDBBufferedValueReader() {}

  /** EIP-170 caps deployed contract code at 24576 bytes; keep a little headroom. */
  private static final int VALUE_BUFFER_CAPACITY = (24 * 1024) + 64;

  /** Code is keyed by a 32-byte hash; 64 bytes leaves headroom for any other small key. */
  private static final int KEY_BUFFER_CAPACITY = 64;

  private static final ThreadLocal<ByteBuffer> KEY_BUFFER =
      ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(KEY_BUFFER_CAPACITY));

  private static final ThreadLocal<ByteBuffer> VALUE_BUFFER =
      ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(VALUE_BUFFER_CAPACITY));

  /**
   * Reads the value for the given key using a reusable thread-local direct buffer.
   *
   * @param db the RocksDB instance (or subclass, e.g. OptimisticTransactionDB)
   * @param readOptions the read options to use (may carry a snapshot)
   * @param handle the column family handle to read from
   * @param key the key bytes
   * @return the value, or empty if the key is absent
   * @throws RocksDBException if the underlying read fails
   */
  static Optional<byte[]> get(
      final RocksDB db,
      final ReadOptions readOptions,
      final ColumnFamilyHandle handle,
      final byte[] key)
      throws RocksDBException {

    // Key buffer: code-hash keys are always 32 bytes, but size up defensively if ever larger.
    ByteBuffer keyBuffer = KEY_BUFFER.get();
    if (key.length > keyBuffer.capacity()) {
      keyBuffer = ByteBuffer.allocateDirect(key.length);
    }
    keyBuffer.clear();
    keyBuffer.put(key).flip();

    final ByteBuffer valueBuffer = VALUE_BUFFER.get();
    valueBuffer.clear();

    // RocksDB writes into valueBuffer and returns the true value size (NOT_FOUND if absent).
    final int valueSize = db.get(handle, readOptions, keyBuffer, valueBuffer);
    if (valueSize == RocksDB.NOT_FOUND) {
      return Optional.empty();
    }
    if (valueSize > valueBuffer.capacity()) {
      // Value larger than our reusable buffer: fall back to a normal allocating get.
      return Optional.ofNullable(db.get(handle, readOptions, key));
    }

    // Exactly one right-sized heap array; copy from the (reused) direct buffer.
    final byte[] value = new byte[valueSize];
    valueBuffer.position(0).limit(valueSize);
    valueBuffer.get(value);
    return Optional.of(value);
  }
}

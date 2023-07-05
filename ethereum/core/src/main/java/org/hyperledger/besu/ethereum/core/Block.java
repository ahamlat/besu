/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import java.util.Objects;

import org.apache.tuweni.bytes.Bytes;

public class Block {

  private final BlockHeader header;
  private final BlockBody body;
  private final boolean transactionsRootVerified;
  private boolean receiptsRootVerified;

  public Block(final BlockHeader header, final BlockBody body) {
    this.header = header;
    this.body = body;
    this.transactionsRootVerified = false;
    this.receiptsRootVerified = false;
  }

  public Block(
          final BlockHeader header,
          final BlockBody body,
          final boolean transactionsRootVerified,
          final boolean receiptsRootVerified) {
    this.header = header;
    this.body = body;
    this.transactionsRootVerified = transactionsRootVerified;
    this.receiptsRootVerified = receiptsRootVerified;
  }

  public BlockHeader getHeader() {
    return header;
  }

  public BlockBody getBody() {
    return body;
  }

  public Hash getHash() {
    return header.getHash();
  }

  public Bytes toRlp() {
    return RLP.encode(this::writeTo);
  }

  public int calculateSize() {
    return toRlp().size();
  }

  public boolean isTransactionsRootVerified() {
    return transactionsRootVerified;
  }

  public boolean isReceiptsRootVerified() {
    return receiptsRootVerified;
  }

  public void setReceiptsRootVerified(final boolean receiptsRootVerified) {
    this.receiptsRootVerified = receiptsRootVerified;
  }

  public void writeTo(final RLPOutput out) {
    out.startList();

    header.writeTo(out);
    body.writeTo(out);

    out.endList();
  }

  public static Block readFrom(final RLPInput in, final BlockHeaderFunctions hashFunction) {
    in.enterList();
    final BlockHeader header = BlockHeader.readFrom(in, hashFunction);
    final BlockBody body = BlockBody.readFrom(in, hashFunction);
    in.leaveList();

    return new Block(header, body);
  }

  @Override
  public boolean equals(final Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Block)) {
      return false;
    }
    final Block other = (Block) obj;
    return header.equals(other.header) && body.equals(other.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(header, body);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("Block{");
    sb.append("header=").append(header).append(", ");
    sb.append("body=").append(body);
    return sb.append("}").toString();
  }

  public String toLogString() {
    return getHeader().toLogString();
  }
}
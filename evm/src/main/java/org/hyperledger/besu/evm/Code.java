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
package org.hyperledger.besu.evm;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.code.OpcodeInfo;
import org.hyperledger.besu.evm.operation.JumpDestOperation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.google.common.base.MoreObjects;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.apache.tuweni.bytes.Bytes;

/** Represents EVM code associated with an account. */
public class Code {

  /** The constant EMPTY_CODE. */
  public static final Code EMPTY_CODE = new Code(Bytes.EMPTY);

  /** The bytes representing the code. */
  private final Bytes bytes;

  /** The hash of the code, needed for accessing metadata about the bytecode */
  private Hash codeHash;

  private Integer size;

  /** Bit mask for jump destinations, used to optimize JUMP/JUMPI operations */
  private long[] jumpDestBitMask = null;

  /**
   * Widest byte vector the running CPU supports (64 lanes on AVX-512, 32 on AVX2, 16 on NEON). Used
   * by the experimental {@link #calculateJumpDestBitMaskVector()} jumpdest scan.
   */
  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

  /** Number of byte lanes in {@link #BYTE_SPECIES}. */
  private static final int BYTE_LANES = BYTE_SPECIES.length();

  /** {@code JUMPDEST} (0x5b) as a signed byte, for vector lane comparisons. */
  private static final byte JUMPDEST_BYTE = (byte) JumpDestOperation.OPCODE;

  /**
   * Public constructor.
   *
   * @param byteCode The byte representation of the code.
   */
  public Code(final Bytes byteCode) {
    this(byteCode, byteCode.isEmpty() ? Hash.EMPTY : null);
  }

  /**
   * Public constructor.
   *
   * @param byteCode The byte representation of the code.
   * @param codeHash the hash of the bytecode
   */
  public Code(final Bytes byteCode, final Hash codeHash) {
    this.bytes = Bytes.wrap(byteCode.toArrayUnsafe());
    this.codeHash = codeHash;
  }

  /**
   * Returns true if the object is equal to this; otherwise false.
   *
   * @param other The object to compare this with.
   * @return True if the object is equal to this, otherwise false.
   */
  @Override
  public boolean equals(final Object other) {
    if (other == null) return false;
    if (other == this) return true;
    if (!(other instanceof Code that)) return false;

    return this.getCodeHash().equals(that.getCodeHash());
  }

  @Override
  public int hashCode() {
    return bytes.hashCode();
  }

  /**
   * Size of the Code, in bytes
   *
   * @return The number of bytes in the code.
   */
  public int getSize() {
    if (size == null) {
      size = bytes.size();
    }

    return size;
  }

  /**
   * Get the bytes for the code.
   *
   * @return code bytes.
   */
  public Bytes getBytes() {
    return bytes;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("bytes", bytes).toString();
  }

  /**
   * Hash of the code
   *
   * @return hash of the code.
   */
  public Hash getCodeHash() {
    if (codeHash != null) {
      return codeHash;
    }

    codeHash = Hash.hash(bytes);
    return codeHash;
  }

  /**
   * Is the target jump location valid?
   *
   * @param jumpDestination index from PC=0.
   * @return true if the operation is both a valid opcode and a JUMPDEST
   */
  public boolean isJumpDestInvalid(final int jumpDestination) {
    if (jumpDestination < 0 || jumpDestination >= getSize()) {
      return true;
    }

    if (jumpDestBitMask == null) {
      jumpDestBitMask = calculateJumpDestBitMask();
    }

    // This selects which long in the array holds the bit for the given offset:
    //	1)	>>> 6 is equivalent to jumpDestination / 64
    //	2)	Each long holds 64 bits, so this finds the correct chunk
    final long targetLong = jumpDestBitMask[jumpDestination >>> 6];

    // 1) & 0x3F is jumpDestination % 64
    // 2)	1L << ... gives a mask for the specific bit in that long
    final long targetBit = 1L << (jumpDestination & 0x3F);

    // If the bit is not set, then it is an invalid jump destination
    return (targetLong & targetBit) == 0L;
  }

  /**
   * A more readable representation of the hex bytes, including whitespace and comments after hashes
   *
   * @return The pretty printed code
   */
  public String prettyPrint() {
    int i = 0;
    int len = bytes.size();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(out);
    ps.println("0x # Legacy EVM Code");
    while (i < len) {
      i += printInstruction(i, ps);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * Returns a bitmask of valid jump destinations for this code. The bitmask is an array of longs,
   * where each bit represents a potential jump destination in the code.
   *
   * @return an array of long values representing the jump destinations, or null if not set
   */
  public long[] getJumpDestBitMask() {
    return jumpDestBitMask;
  }

  /**
   * Sets the jump destination bitmask for this code. This method is intended to be used by the
   * EVM's JumpService to set the valid jump destinations for the code.
   *
   * @param jumpDestBitMask an array of long values representing the jump destinations
   */
  public void setJumpDestBitMask(final long[] jumpDestBitMask) {
    this.jumpDestBitMask = jumpDestBitMask;
  }

  /**
   * Computes a bitmask where each bit set to 1 indicates a valid `JUMPDEST` opcode in the EVM
   * bytecode. The bitmap is organized in 64-byte chunks, each represented as a `long` (64 bits).
   * This is used for efficiently validating dynamic jumps (`JUMP`, `JUMPI`) at runtime.
   */
  long[] calculateJumpDestBitMask() {
    // Total number of bytes in the bytecode
    final int size = getSize();

    // Allocate enough longs to cover all bytes, one long (64 bits) per 64-byte chunk
    final long[] bitmap = new long[(size >> 6) + 1];

    // Get the raw EVM bytecode as a byte array (no copying)
    final byte[] rawCode = getBytes().toArrayUnsafe();
    final int length = rawCode.length;

    // Iterate through the bytecode
    for (int i = 0; i < length; ) {
      // One 64-bit entry corresponds to 64 bytecode positions
      long thisEntry = 0L;

      // Compute which bitmap entry we are in (i / 64)
      final int entryPos = i >> 6;

      // Compute the number of bytes we can safely examine in this 64-byte window
      final int max = Math.min(64, length - (entryPos << 6));

      // j is the position within this 64-byte window
      int j = i & 0x3F;

      // Scan through this 64-byte chunk of the bytecode
      for (; j < max; i++, j++) {
        final byte operationNum = rawCode[i];

        // Only JUMPDEST (0x5b) and PUSH1–PUSH32 (0x60–0x7f) matter. Opcodes >= 0x80 are negative
        // as signed bytes, so this comparison also excludes them for free.
        if (operationNum >= JumpDestOperation.OPCODE) {
          if (operationNum == JumpDestOperation.OPCODE) {
            thisEntry |= 1L << j; // Set the bit at position j
          } else if (operationNum >= 0x60) {
            // PUSH1–PUSH32 carry (opcode - 0x5f) immediate data bytes to skip
            // (PUSH1 0x60 -> 1 ... PUSH32 0x7f -> 32).
            final int skip = operationNum - 0x5f;
            i += skip;
            j += skip;
          }
          // 0x5c–0x5f (TLOAD/TSTORE/MCOPY/PUSH0) carry no data and are not jump destinations.
        }
      }

      // Store the computed bitmask for this 64-byte chunk
      bitmap[entryPos] = thisEntry;
    }

    // Return the full jump destination bitmask
    return bitmap;
  }

  /**
   * Experimental SIMD variant of {@link #calculateJumpDestBitMask()} built on the (incubating)
   * Vector API.
   *
   * <p>Jumpdest analysis cannot be vectorized naively because PUSH opcodes carry immediate data: the
   * meaning of byte {@code i} depends on having parsed everything before it. The strategy here,
   * following the "uniform vs. divergent" pattern from Emanuel Peter's Vector-API control-flow
   * write-up, keeps the scalar cursor at a known opcode boundary and processes a full vector at a
   * time:
   *
   * <ul>
   *   <li><b>Uniform chunk</b> — if a vector-wide window contains no PUSH opcode, every byte is an
   *       opcode, so comparing the lanes against {@code JUMPDEST} yields the bitmap bits directly
   *       (one {@code compare} + {@code toLong}).
   *   <li><b>Divergent chunk</b> — if a PUSH is present, the bits up to the first PUSH are still
   *       trustworthy; emit them, then hand the PUSH itself to the scalar path so its immediate data
   *       is skipped and the opcode boundary is re-established.
   * </ul>
   *
   * <p>This deliberately avoids {@code compress}/masked-store operations, which degrade to slow
   * scalar fallbacks on some platforms. It is a constant-factor optimization of the scan only; it
   * does not change the O(n) cost and is intended for benchmarking against the scalar version.
   *
   * @return the jump destination bitmask, identical to {@link #calculateJumpDestBitMask()}
   */
  long[] calculateJumpDestBitMaskVector() {
    final int size = getSize();
    final long[] bitmap = new long[(size >> 6) + 1];
    final byte[] rawCode = getBytes().toArrayUnsafe();
    final int length = rawCode.length;

    int i = 0;
    while (i < length) {
      // Vector fast path: only when a full vector fits. The loop only reaches this point at an
      // opcode boundary, so every lane below is a real opcode unless a PUSH appears in the window.
      if (i + BYTE_LANES <= length) {
        final ByteVector window = ByteVector.fromArray(BYTE_SPECIES, rawCode, i);

        // PUSH1..PUSH32 is the contiguous range 0x60..0x7f. Shifting by 0x60 turns it into 0..0x1f
        // so a single unsigned comparison detects it (see lowerCase example in the write-up).
        final VectorMask<Byte> isPush =
            window.sub((byte) 0x60).compare(VectorOperators.UNSIGNED_LE, (byte) 0x1f);

        final long jumpDests = window.compare(VectorOperators.EQ, JUMPDEST_BYTE).toLong();

        if (!isPush.anyTrue()) {
          // Uniform window: no PUSH, so all lanes are opcodes and the JUMPDEST mask is exact.
          setBits(bitmap, i, jumpDests, BYTE_LANES);
          i += BYTE_LANES;
          continue;
        }

        // Divergent window: trust the JUMPDEST bits before the first PUSH, then let the scalar
        // path consume the PUSH and its immediate data.
        final int firstPush = isPush.firstTrue();
        if (firstPush > 0) {
          setBits(bitmap, i, jumpDests & ((1L << firstPush) - 1), firstPush);
          i += firstPush;
        }
      }

      // Scalar single-instruction step (also drains the final < BYTE_LANES bytes).
      final int op = rawCode[i] & 0xff;
      if (op == JumpDestOperation.OPCODE) {
        bitmap[i >> 6] |= 1L << (i & 63);
      }
      if (op >= 0x60 && op <= 0x7f) {
        i += (op - 0x5f) + 1; // skip the PUSH opcode and its immediate data bytes
      } else {
        i++;
      }
    }
    return bitmap;
  }

  /**
   * ORs {@code count} freshly computed jumpdest bits into the bitmap at absolute byte position
   * {@code pos}. Bit {@code q} of {@code bits} corresponds to byte {@code pos + q}. The run may
   * straddle the boundary between two 64-byte entries, in which case it is split across two longs.
   */
  private static void setBits(
      final long[] bitmap, final int pos, final long bits, final int count) {
    final int word = pos >> 6;
    final int offset = pos & 63;
    bitmap[word] |= bits << offset;
    if (offset + count > 64) {
      bitmap[word + 1] |= bits >>> (64 - offset);
    }
  }

  /**
   * Prints an individual instruction, including immediate data
   *
   * @param offset Offset within the code
   * @param out the print stream to write to
   * @return the number of bytes to advance the PC (includes consideration of immediate arguments)
   */
  public int printInstruction(final int offset, final PrintStream out) {
    int codeByte = bytes.get(offset) & 0xff;
    OpcodeInfo info = OpcodeInfo.getOpcode(codeByte);
    String push = "";
    String decimalPush = "";
    if (info.pcAdvance() > 1) {
      int start = Math.min(bytes.size(), offset + 1);
      int end = Math.min(bytes.size(), info.pcAdvance() - 1);
      Bytes slice = bytes.slice(start, end);
      push = slice.toUnprefixedHexString();
      if (info.pcAdvance() < 5) {
        decimalPush = "(" + slice.toLong() + ")";
      }
    }
    String name = info.name();
    if (codeByte == 0x5b) {
      name = "JUMPDEST";
    }
    out.printf("%02x%s # [ %d ] %s%s%n", codeByte, push, offset, name, decimalPush);
    return Math.max(1, info.pcAdvance());
  }
}

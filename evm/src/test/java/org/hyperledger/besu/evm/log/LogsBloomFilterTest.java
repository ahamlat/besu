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
package org.hyperledger.besu.evm.log;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.LogsBloomFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LogsBloomFilterTest {

  @Test
  void logsBloomFilter() {
    final Address address = Address.fromHexString("0x095e7baea6a6c7c4c2dfeb977efac326af552d87");
    final Bytes data = Bytes.fromHexString("0x0102");
    final List<LogTopic> topics = new ArrayList<>();
    topics.add(
        LogTopic.fromHexString(
            "0x0000000000000000000000000000000000000000000000000000000000000000"));

    final Log log = new Log(address, data, topics);
    final LogsBloomFilter bloom = LogsBloomFilter.builder().insertLog(log).build();

    Assertions.assertThat(bloom.getBytes())
        .isEqualTo(
            Bytes.fromHexString(
                "0x00000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000000800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000020000000000040000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  void testInsertRawLog() {
    final Address address = Address.fromHexString("0x095e7baea6a6c7c4c2dfeb977efac326af552d87");
    final List<Bytes> topics = new ArrayList<>();
    topics.add(
        Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000"));

    final LogsBloomFilter bloom =
        LogsBloomFilter.builder().insertRawLog(address.getBytes(), topics).build();

    Assertions.assertThat(bloom.getBytes())
        .isEqualTo(
            Bytes.fromHexString(
                "0x00000000000000001000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000000800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000020000000000040000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  void insertLogsMatchesInsertingEachLog() {
    final Address token = Address.fromHexString("0x095e7baea6a6c7c4c2dfeb977efac326af552d87");
    final LogTopic transfer =
        LogTopic.fromHexString(
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    final LogTopic from = LogTopic.wrap(Bytes32.leftPad(Bytes.of(0x11)));
    final List<Log> logs =
        IntStream.range(0, 100)
            .mapToObj(
                i ->
                    new Log(
                        i % 10 == 0 ? Address.fromHexString("0x" + "%040x".formatted(i)) : token,
                        Bytes.of(i),
                        List.of(transfer, from, LogTopic.wrap(Bytes32.leftPad(Bytes.of(i % 7))))))
            .toList();

    assertInsertLogsMatchesInsertingEachLog(logs);
  }

  @Test
  void insertLogsMatchesInsertingEachLogForRandomizedLogs() {
    final Random random = new Random(0xB1005L);
    final List<Address> addresses =
        IntStream.range(0, 8)
            .mapToObj(i -> Address.fromHexString("0x" + "%040x".formatted(i + 1)))
            .toList();
    final List<LogTopic> topicPool =
        IntStream.range(0, 16)
            .mapToObj(i -> LogTopic.wrap(Bytes32.leftPad(Bytes.ofUnsignedInt(i + 1))))
            .toList();

    for (int sample = 0; sample < 200; sample++) {
      final List<Log> logs = new ArrayList<>();
      for (int logIndex = 0; logIndex < sample % 11; logIndex++) {
        final Log previous = logs.isEmpty() ? null : logs.getLast();
        final Address logger =
            previous != null && random.nextBoolean()
                ? previous.getLogger()
                : addresses.get(random.nextInt(addresses.size()));
        final List<LogTopic> previousTopics = previous == null ? List.of() : previous.getTopics();
        final int topicCount = (sample + logIndex) % 5;
        final List<LogTopic> topics = new ArrayList<>(topicCount);
        for (int topicIndex = 0; topicIndex < topicCount; topicIndex++) {
          topics.add(
              topicIndex < previousTopics.size() && random.nextBoolean()
                  ? previousTopics.get(topicIndex)
                  : topicPool.get(random.nextInt(topicPool.size())));
        }
        logs.add(new Log(logger, Bytes.ofUnsignedInt(sample * 100 + logIndex), topics));
      }

      assertInsertLogsMatchesInsertingEachLog(logs);
    }
  }

  private static void assertInsertLogsMatchesInsertingEachLog(final List<Log> logs) {
    final LogsBloomFilter.Builder oneByOne = LogsBloomFilter.builder();
    logs.forEach(oneByOne::insertLog);

    Assertions.assertThat(LogsBloomFilter.builder().insertLogs(logs).build().getBytes())
        .isEqualTo(oneByOne.build().getBytes());
  }
}

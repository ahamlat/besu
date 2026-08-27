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
package org.hyperledger.besu.consensus.qbft.adaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.List;

import org.junit.jupiter.api.Test;

class QbftLocalBlockExecutionCacheTest {

  @Test
  void takeReturnsMatchingExecutionAndRemovesIt() {
    final QbftLocalBlockExecutionCache cache = new QbftLocalBlockExecutionCache();
    final MutableWorldState worldState = mock(MutableWorldState.class);
    final Hash hash = Hash.fromHexStringLenient("1");
    cache.store(new QbftLocalBlockExecutionCache.CachedExecution(hash, worldState, List.of()));

    assertThat(cache.take(hash)).isPresent();
    assertThat(cache.take(hash)).isEmpty();
  }

  @Test
  void storeClosesPreviousWorldState() throws Exception {
    final QbftLocalBlockExecutionCache cache = new QbftLocalBlockExecutionCache();
    final MutableWorldState first = mock(MutableWorldState.class);
    final MutableWorldState second = mock(MutableWorldState.class);
    cache.store(
        new QbftLocalBlockExecutionCache.CachedExecution(
            Hash.fromHexStringLenient("1"), first, List.of()));
    cache.store(
        new QbftLocalBlockExecutionCache.CachedExecution(
            Hash.fromHexStringLenient("2"), second, List.of()));

    verify(first).close();
  }
}

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
package org.hyperledger.besu.evm.precompile;

import org.hyperledger.besu.crypto.altbn128.Bn254Add;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP196;

import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.validation.constraints.NotNull;
import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The AltBN128Add precompiled contract. */
public class AltBN128AddPrecompiledContract extends AbstractAltBnPrecompiledContract {
  private static final Logger LOG = LoggerFactory.getLogger(AltBN128AddPrecompiledContract.class);
  private static final int PARAMETER_LENGTH = 128;
  private static final String PRECOMPILE_NAME = "BN254_ADD";

  private final long gasCost;
  private static final Cache<Bytes, PrecompileInputResultTuple> bnAddCache =
      AbstractPrecompiledContract.resultCacheBuilder().build();

  AltBN128AddPrecompiledContract(final GasCalculator gasCalculator, final long gasCost) {
    super(
        PRECOMPILE_NAME,
        gasCalculator,
        LibGnarkEIP196.EIP196_ADD_OPERATION_RAW_VALUE,
        PARAMETER_LENGTH,
        LibGnarkEIP196.EIP196_PREALLOCATE_FOR_RESULT_BYTES);
    this.gasCost = gasCost;
  }

  @Override
  public long gasRequirement(final Bytes input) {
    return gasCost;
  }

  @NotNull
  @Override
  public PrecompileContractResult computePrecompile(
      final Bytes input, @NotNull final MessageFrame messageFrame) {

    PrecompileInputResultTuple res;
    Bytes cacheKey = null;
    final Bytes cachedInput =
        input.size() > PARAMETER_LENGTH ? input.slice(0, PARAMETER_LENGTH) : input;

    if (enableResultCaching) {
      cacheKey = getCacheKey(input, PARAMETER_LENGTH);
      res = bnAddCache.getIfPresent(cacheKey);
      if (res != null) {
        if (res.cachedInput().equals(cachedInput)) {
          cacheEventConsumer.accept(new CacheEvent(PRECOMPILE_NAME, CacheMetric.HIT));
          return res.cachedResult();
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug(
                "false positive altbn128Add {}, cache key {}, cached input: {}, input: {}",
                input.getClass().getSimpleName(),
                cacheKey,
                res.cachedInput().toHexString(),
                cachedInput.toHexString());
          }

          cacheEventConsumer.accept(new CacheEvent(PRECOMPILE_NAME, CacheMetric.FALSE_POSITIVE));
        }
      } else {
        cacheEventConsumer.accept(new CacheEvent(PRECOMPILE_NAME, CacheMetric.MISS));
      }
    }
    if (useNative) {
      res =
          new PrecompileInputResultTuple(
              enableResultCaching ? cachedInput.copy() : input, computeNative(input, messageFrame));
    } else {
      res =
          new PrecompileInputResultTuple(
              enableResultCaching ? cachedInput.copy() : input, computeDefault(cachedInput));
    }
    if (cacheKey != null) {
      bnAddCache.put(cacheKey, res);
    }
    return res.cachedResult();
  }

  private static PrecompileContractResult computeDefault(final Bytes input) {
    final byte[] result = new byte[64];
    final byte[] raw = input.toArrayUnsafe();
    if (!Bn254Add.ecAdd(raw, 0, raw.length, result)) {
      return PrecompileContractResult.halt(
          null, Optional.of(ExceptionalHaltReason.PRECOMPILE_ERROR));
    }
    return PrecompileContractResult.success(Bytes.wrap(result));
  }
}

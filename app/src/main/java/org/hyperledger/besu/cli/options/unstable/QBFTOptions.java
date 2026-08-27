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
package org.hyperledger.besu.cli.options.unstable;

import picocli.CommandLine;

/** Handles configuration options for QBFT consensus */
public class QBFTOptions {

  /** Default constructor */
  private QBFTOptions() {}

  /**
   * Create a new instance of QBFTOptions
   *
   * @return a new instance of QBFTOptions
   */
  public static QBFTOptions create() {
    return new QBFTOptions();
  }

  @CommandLine.Option(
      names = {"--Xqbft-enable-early-round-change"},
      description =
          "Enable early round change upon receiving f+1 valid future Round Change messages from different validators (experimental)",
      hidden = true)
  private boolean enableEarlyRoundChange = false;

  @CommandLine.Option(
      names = {"--Xqbft-single-validator-fast-path"},
      description =
          "Skip redundant QBFT block execution when this node is the only validator. The block is executed once during transaction selection. Proposal validation and import do not re-execute the block. Use only on a 1-validator network (experimental)",
      hidden = true)
  private boolean singleValidatorFastPath = false;

  /**
   * Is early round change enabled boolean.
   *
   * @return true if early round change is enabled
   */
  public boolean isEarlyRoundChangeEnabled() {
    return enableEarlyRoundChange;
  }

  /**
   * Whether the single-validator fast path is enabled.
   *
   * @return true if redundant QBFT block execution should be skipped for a 1-validator network
   */
  public boolean isSingleValidatorFastPathEnabled() {
    return singleValidatorFastPath;
  }
}

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
package org.hyperledger.besu.ethereum.permissioning.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.fail;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.permissioning.LocalPermissioningConfiguration;
import org.hyperledger.besu.ethereum.permissioning.PermissioningConfiguration;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AccountPermissioningControllerFactoryTest {

  @Mock private TransactionSimulator transactionSimulator;

  private final MetricsSystem metricsSystem = new NoOpMetricsSystem();

  @Test
  public void createWithNullPermissioningConfigShouldReturnEmpty() {
    Optional<AccountPermissioningController> controller =
        AccountPermissioningControllerFactory.create(
            null, transactionSimulator, metricsSystem, Collections.emptyList());

    Assertions.assertThat(controller).isEmpty();
  }

  @Test
  public void createLocalConfigWithAccountPermissioningDisabledShouldReturnEmpty() {
    LocalPermissioningConfiguration localConfig = LocalPermissioningConfiguration.createDefault();
    assertThat(localConfig.isAccountAllowlistEnabled()).isFalse();

    PermissioningConfiguration permissioningConfiguration =
        new PermissioningConfiguration(Optional.of(localConfig));

    Optional<AccountPermissioningController> controller =
        AccountPermissioningControllerFactory.create(
            permissioningConfiguration,
            transactionSimulator,
            metricsSystem,
            Collections.emptyList());

    Assertions.assertThat(controller).isEmpty();
  }

  @Test
  public void createLocalConfigOnlyControllerShouldReturnExpectedController() {
    LocalPermissioningConfiguration localConfig = localConfig();
    assertThat(localConfig.isAccountAllowlistEnabled()).isTrue();

    PermissioningConfiguration permissioningConfiguration =
        new PermissioningConfiguration(Optional.of(localConfig));

    Optional<AccountPermissioningController> controller =
        AccountPermissioningControllerFactory.create(
            permissioningConfiguration,
            transactionSimulator,
            metricsSystem,
            Collections.emptyList());

    Assertions.assertThat(controller).isNotEmpty();
    assertThat(controller.get().getAccountLocalConfigPermissioningController()).isNotEmpty();
  }

  private LocalPermissioningConfiguration localConfig() {
    LocalPermissioningConfiguration localPermissioningConfiguration =
        LocalPermissioningConfiguration.createDefault();
    localPermissioningConfiguration.setAccountAllowlist(
        Arrays.asList(Address.fromHexString("0x00").toString()));
    localPermissioningConfiguration.setAccountPermissioningConfigFilePath(
        createTempFile().getPath());
    return localPermissioningConfiguration;
  }

  private File createTempFile() {
    try {
      File file = File.createTempFile("test", "test");
      file.deleteOnExit();
      return file;
    } catch (IOException e) {
      fail("Test failed to create temporary file", e);
    }
    return null;
  }
}

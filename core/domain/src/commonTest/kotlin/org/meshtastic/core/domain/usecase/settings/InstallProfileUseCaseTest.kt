/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.domain.usecase.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.util.CHANNEL_REPLACEMENT_SLOT_COUNT
import org.meshtastic.core.model.util.MalformedMeshtasticUrlException
import org.meshtastic.core.model.util.getChannelUrl
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.PacketQueueRejectedException
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.FakeRadioInterfaceService
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config.BluetoothConfig
import org.meshtastic.proto.Config.DeviceConfig
import org.meshtastic.proto.Config.DisplayConfig
import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.NetworkConfig
import org.meshtastic.proto.Config.PositionConfig
import org.meshtastic.proto.Config.PowerConfig
import org.meshtastic.proto.Config.SecurityConfig
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig.ExternalNotificationConfig
import org.meshtastic.proto.ModuleConfig.MQTTConfig
import org.meshtastic.proto.ModuleConfig.MeshBeaconConfig
import org.meshtastic.proto.ModuleConfig.SerialConfig
import org.meshtastic.proto.ModuleConfig.TrafficManagementConfig
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallProfileUseCaseTest {

    private lateinit var radioController: FakeRadioController
    private lateinit var radioInterfaceService: FakeRadioInterfaceService
    private lateinit var radioConfigRepository: FakeRadioConfigRepository
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var restartTrackerScope: CoroutineScope
    private lateinit var restartTracker: NodeRestartTracker
    private lateinit var useCase: InstallProfileUseCase

    @BeforeTest
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        restartTrackerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        radioController = FakeRadioController().apply { setConnectionState(ConnectionState.Connected) }
        radioInterfaceService =
            FakeRadioInterfaceService(restartTrackerScope).apply { setDeviceAddress("x00:11:22:33:44:55") }
        radioConfigRepository = FakeRadioConfigRepository()
        nodeRepository =
            FakeNodeRepository().apply { setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 1234)) }
        restartTracker = NodeRestartTracker(restartTrackerScope)
        useCase =
            InstallProfileUseCase(
                radioController,
                radioInterfaceService,
                radioConfigRepository,
                nodeRepository,
                restartTracker,
            )
    }

    @AfterTest
    fun tearDown() {
        restartTrackerScope.cancel()
    }

    @Test
    fun `empty profile performs no restart or writes`() = runTest(testDispatcher) {
        useCase(1234, DeviceProfile.Builder().build(), User.Builder().build(), isLocal = true)

        assertNoDeviceWrites()
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `concurrent profile installations are serialized without interleaved writes`() = runTest(testDispatcher) {
        val firstCommitReached = CompletableDeferred<Unit>()
        val releaseFirstCommit = CompletableDeferred<Unit>()
        var commitCount = 0
        radioController.onEditSettingsCommitted = {
            commitCount += 1
            if (commitCount == 1) {
                firstCommitReached.complete(Unit)
                releaseFirstCommit.await()
            }
            commitAndRestart()
        }

        val first = async { useCase(1234, DeviceProfile.Builder().also { wb -> wb.long_name = "First" }.build(), User.Builder().build(), isLocal = true) }
        firstCommitReached.await()
        val second = async { useCase(1234, DeviceProfile.Builder().also { wb -> wb.short_name = "Second" }.build(), User.Builder().build(), isLocal = true) }
        testScheduler.runCurrent()

        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertEquals(listOf("begin", "owner", "commit"), radioController.adminOperations)

        releaseFirstCommit.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, radioController.adminOperations.count { it == "begin" })
        assertEquals(
            listOf("begin", "owner", "commit", "begin", "owner", "commit"),
            radioController.adminOperations,
        )
    }

    @Test
    fun `cancellation while waiting for restart clears the expected-restart state`() = runTest(testDispatcher) {
        val departureObserved = CompletableDeferred<Unit>()
        radioController.onEditSettingsCommitted = {
            emitDeparture()
            departureObserved.complete(Unit)
        }
        val installation = launch { useCase(1234, DeviceProfile.Builder().also { wb -> wb.long_name = "Cancelled" }.build(), User.Builder().build(), isLocal = true) }

        departureObserved.await()
        assertTrue(restartTracker.restartExpected.value)
        installation.cancelAndJoin()

        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `profile installation reports each planned stage`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }
        val progress = mutableListOf<ProfileInstallProgress>()
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.long_name = "Updated node"
        wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig(enabled = true) }.build()
    }.build()

        useCase(1234, profile, User.Builder().also { wb -> wb.long_name = "Old node" }.build(), isLocal = true, progress::add)

        assertEquals(listOf(ProfileInstallProgress(1, 2), ProfileInstallProgress(2, 2)), progress)
    }

    @Test
    fun `profile snapshot wait is bounded before any device write`() = runTest(testDispatcher) {
        val stalledRepository =
            object : RadioConfigRepository by radioConfigRepository {
                override val localConfigFlow = MutableSharedFlow<LocalConfig>()
            }
        useCase = useCaseWith(stalledRepository)

        val result = async { runCatching { useCase(1234, DeviceProfile.Builder().build(), User.Builder().build(), isLocal = true) } }
        advanceTimeBy(10_001)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("Timed out waiting"))
        assertNoDeviceWrites()
    }

    @Test
    fun `profile snapshot is rejected after connection lifecycle rollover`() = runTest(testDispatcher) {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        useCase = useCaseWith(gatedConfigRepository(snapshotStarted, releaseSnapshot))

        val result = async { runCatching { useCase(1234, DeviceProfile.Builder().build(), User.Builder().build(), isLocal = true) } }
        snapshotStarted.await()
        radioController.setConnectionState(ConnectionState.Disconnected)
        radioController.setConnectionState(ConnectionState.Connected)
        releaseSnapshot.complete(Unit)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("lifecycle changed"))
        assertNoDeviceWrites()
    }

    @Test
    fun `profile snapshot is rejected after transport selection changes`() = runTest(testDispatcher) {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        useCase = useCaseWith(gatedConfigRepository(snapshotStarted, releaseSnapshot))

        val result = async { runCatching { useCase(1234, DeviceProfile.Builder().build(), User.Builder().build(), isLocal = true) } }
        snapshotStarted.await()
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        releaseSnapshot.complete(Unit)

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("transport changed"))
        assertNoDeviceWrites()
    }

    @Test
    fun `transaction on TCP waits for the firmware reboot and handshake`() = runTest(testDispatcher) {
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US) }.build() }.build()

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertTrue(radioController.editSettingsCalled)
        assertEquals(ConnectionState.Connected, radioController.connectionState.value)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `fast restart cycle cannot be lost to connection state conflation`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = {
            applyTransactionToDeviceState()
            emitFastRestartCycle()
        }
        val profile = DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb -> wb.device = DeviceConfig() }.build() }.build()
        val baselineEpochs = radioController.connectionEpochs.value

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(ConnectionState.Connected, radioController.connectionState.value)
        assertEquals(baselineEpochs.departures + 1, radioController.connectionEpochs.value.departures)
        assertEquals(
            baselineEpochs.completedHandshakes + 1,
            radioController.connectionEpochs.value.completedHandshakes,
        )
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `reconnect must follow the latest departure rather than an older handshake`() = runTest(testDispatcher) {
        val allowLatestReconnect = CompletableDeferred<Unit>()
        radioController.onEditSettingsCommitted = {
            applyTransactionToDeviceState()
            emitFastRestartCycle()
            radioController.setConnectionState(ConnectionState.Disconnected)
            backgroundScope.launch {
                allowLatestReconnect.await()
                radioController.setConnectionState(ConnectionState.Connected)
            }
        }
        radioController.onStandaloneModuleConfig = { emitFastRestartCycle() }
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb -> wb.device = DeviceConfig() }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig(enabled = true) }.build()
    }.build()

        val installation = async { useCase(1234, profile, User.Builder().build(), isLocal = true) }
        testScheduler.runCurrent()

        assertFalse(installation.isCompleted)
        assertTrue(radioController.moduleConfigWrites.isEmpty())

        allowLatestReconnect.complete(Unit)
        testScheduler.runCurrent()
        installation.await()

        assertEquals(MQTTConfig(enabled = true), radioController.allModuleConfigs.single().mqtt)
    }

    @Test
    fun `version-only remnants do not open an empty transaction`() = runTest(testDispatcher) {
        radioController.onStandaloneModuleConfig = { emitFastRestartCycle() }
        radioController.onStandaloneConfig = { emitDeparture() }
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb ->
        wb.bluetooth = BluetoothConfig(enabled = false)
        wb.version = 1
    }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = MQTTConfig(enabled = true)
        wb.version = 1
    }.build()
    }.build()

        val outcome = useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertFalse(radioController.editSettingsCalled)
        assertEquals(listOf("module:update", "config:update"), radioController.adminOperations)
        // Disabling Bluetooth over BLE ends the session intentionally: reconnection is stopped, not retried.
        assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
        assertTrue(radioInterfaceService.disconnectCalled)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `unchanged profile config is skipped without restart or writes`() = runTest(testDispatcher) {
        val currentConfig =
            LocalConfig.Builder().also { wb ->
        wb.device = DeviceConfig()
        wb.position = PositionConfig()
        wb.power = PowerConfig()
        wb.network = NetworkConfig(wifi_enabled = true, wifi_ssid = "existing")
        wb.display = DisplayConfig(compass_north_top = true)
        wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US)
        wb.bluetooth = BluetoothConfig(enabled = true)
        wb.security = SecurityConfig()
        wb.version = 7
    }.build()
        val currentModuleConfig =
            LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = MQTTConfig(enabled = true)
        wb.serial = SerialConfig(enabled = true)
        wb.external_notification = ExternalNotificationConfig(enabled = true)
        wb.traffic_management = TrafficManagementConfig(position_min_interval_secs = 30)
        wb.mesh_beacon = MeshBeaconConfig(broadcast_message = "existing beacon")
        wb.version = 9
    }.build()
        radioConfigRepository.setLocalConfigDirect(currentConfig)
        radioConfigRepository.setLocalModuleConfigDirect(currentModuleConfig)

        val currentUser = User.Builder().also { wb ->
        wb.long_name = "Current User"
        wb.short_name = "CUR"
        wb.is_unmessagable = true
    }.build()
        useCase(
            1234,
            DeviceProfile.Builder().also { wb ->
        wb.long_name = currentUser.long_name
        wb.short_name = currentUser.short_name
        wb.is_unmessagable = currentUser.is_unmessagable
        wb.config = currentConfig
        wb.module_config = currentModuleConfig
    }.build(),
            currentUser,
            isLocal = true,
        )

        assertNoDeviceWrites()
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `mixed profile sends only changed ordinary and transport settings`() = runTest(testDispatcher) {
        val currentConfig =
            LocalConfig.Builder().also { wb ->
        wb.device = DeviceConfig()
        wb.display = DisplayConfig(compass_north_top = false)
        wb.network = NetworkConfig(wifi_enabled = false)
        wb.bluetooth = BluetoothConfig(enabled = true)
    }.build()
        val currentModuleConfig =
            LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = MQTTConfig(enabled = true)
        wb.serial = SerialConfig(enabled = false)
        wb.external_notification = ExternalNotificationConfig(enabled = true)
        wb.mesh_beacon = MeshBeaconConfig(broadcast_message = "old beacon")
    }.build()
        radioConfigRepository.setLocalConfigDirect(currentConfig)
        radioConfigRepository.setLocalModuleConfigDirect(currentModuleConfig)
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }

        useCase(
            1234,
            DeviceProfile.Builder().also { wb ->
        wb.config =
            currentConfig
                .newBuilder()
                .also { cfg -> cfg.display = DisplayConfig.Builder().also { d -> d.compass_north_top = true }.build() }
                .build()
        wb.module_config =
            currentModuleConfig
                .newBuilder()
                .also { mc ->
                    mc.serial = SerialConfig.Builder().also { sc -> sc.enabled = true }.build()
                    mc.mesh_beacon = MeshBeaconConfig.Builder().also { mb -> mb.broadcast_message = "new beacon" }.build()
                }
                .build()
    }.build(),
            User.Builder().build(),
            isLocal = true,
        )

        assertEquals(
            listOf(DisplayConfig(compass_north_top = true)),
            radioController.configWrites.mapNotNull { it.config.display },
        )
        assertEquals(
            listOf(MeshBeaconConfig(broadcast_message = "new beacon"), SerialConfig(enabled = true)),
            radioController.allModuleConfigs.mapNotNull { it.mesh_beacon ?: it.serial },
        )
        assertEquals(
            listOf("begin", "config:update", "module:update", "commit", "module:update"),
            radioController.adminOperations,
        )
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `traffic management profile is restored inside the ordinary transaction`() = runTest(testDispatcher) {
        val trafficManagement = TrafficManagementConfig(position_min_interval_secs = 30)
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(
            1234,
            DeviceProfile.Builder().also { wb -> wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.traffic_management = trafficManagement }.build() }.build(),
            User.Builder().build(),
            isLocal = true,
        )

        assertTrue(radioController.editSettingsCalled)
        assertEquals(trafficManagement, radioController.localModuleConfigs.single().traffic_management)
        assertEquals(listOf("begin", "module:update", "commit"), radioController.adminOperations)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `matching channel profile skips the transaction and restart`() = runTest(testDispatcher) {
        val settings =
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).map { index ->
                ChannelSettings.Builder().also { wb ->
        wb.name = "Channel $index"
        wb.psk = byteArrayOf(index.toByte()).toByteString()
    }.build()
            }
        radioConfigRepository.setChannelSet(ChannelSet.Builder().also { wb -> wb.settings = settings }.build())
        val profile = DeviceProfile.Builder().also { wb -> wb.channel_url = ChannelSet.Builder().also { wb -> wb.settings = settings }.build().getChannelUrl().toString() }.build()

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertNoDeviceWrites()
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `TCP profile restores network last after reconnecting from earlier stages`() = runTest(testDispatcher) {
        val mqtt = MQTTConfig(enabled = true)
        val serial = SerialConfig(enabled = true)
        val bluetooth = BluetoothConfig(enabled = true)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb ->
        wb.network = network
        wb.bluetooth = bluetooth
    }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = mqtt
        wb.serial = serial
    }.build()
    }.build()
        radioInterfaceService.setDeviceAddress("t192.0.2.1")
        radioController.onStandaloneModuleConfig = { emitRestartCycle() }
        radioController.onEditSettingsCommitted = { emitDeparture() }

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(listOf(mqtt, serial), radioController.allModuleConfigs.mapNotNull { it.mqtt ?: it.serial })
        assertEquals(bluetooth, radioController.configWrites.first().config.bluetooth)
        assertEquals(network, radioController.configWrites.last().config.network)
        assertEquals(
            listOf("module:update", "module:update", "begin", "config:update", "config:update", "commit"),
            radioController.adminOperations,
        )
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertTrue(restartTracker.restartExpected.value)
    }

    @Test
    fun `USB profile restores serial last after reconnecting from earlier stages`() = runTest(testDispatcher) {
        val mqtt = MQTTConfig(enabled = true)
        val serial = SerialConfig(enabled = true)
        val bluetooth = BluetoothConfig(enabled = true)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb ->
        wb.bluetooth = bluetooth
        wb.network = network
    }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = mqtt
        wb.serial = serial
    }.build()
    }.build()
        radioInterfaceService.setDeviceAddress("s/dev/ttyUSB0")
        radioController.onStandaloneModuleConfig = { config ->
            if (config.serial != null) emitDeparture() else emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(listOf(mqtt, serial), radioController.allModuleConfigs.mapNotNull { it.mqtt ?: it.serial })
        assertEquals(listOf(bluetooth, null), radioController.configWrites.map { it.config.bluetooth })
        assertEquals(listOf(null, network), radioController.configWrites.map { it.config.network })
        assertEquals(
            listOf("module:update", "config:update", "config:update", "module:update"),
            radioController.adminOperations,
        )
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertTrue(restartTracker.restartExpected.value)
    }

    @Test
    fun `BLE terminal transport settings commit together before the connection ends`() = runTest(testDispatcher) {
        val bluetooth = BluetoothConfig(enabled = false)
        val network = NetworkConfig(wifi_enabled = true, wifi_ssid = "new-network")
        radioController.onEditSettingsCommitted = { emitDeparture() }

        val outcome =
            useCase(
                1234,
                DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb ->
        wb.bluetooth = bluetooth
        wb.network = network
    }.build() }.build(),
                User.Builder().build(),
                isLocal = true,
            )

        assertEquals(listOf(bluetooth, null), radioController.configWrites.map { it.config.bluetooth })
        assertEquals(listOf(null, network), radioController.configWrites.map { it.config.network })
        assertEquals(listOf("begin", "config:update", "config:update", "commit"), radioController.adminOperations)
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        // The final write intentionally handed the active transport off. Production closes the expected-restart
        // window
        // before disconnecting so the UI shows the honest disconnected state instead of leaving a stale window
        // open.
        assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `rejected standalone module config fails after the bounded departure wait`() = runTest(testDispatcher) {
        val failure =
            assertFailsWith<IllegalStateException> {
                useCase(
                    1234,
                    DeviceProfile.Builder().also { wb -> wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig(enabled = true) }.build() }.build(),
                    User.Builder().build(),
                    isLocal = true,
                )
            }

        assertEquals("Device did not begin the mqtt profile restart", failure.message)
        assertEquals(20_000L, testScheduler.currentTime)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `failed reconnect clears the expected restart window while disconnected`() = runTest(testDispatcher) {
        radioController.onStandaloneModuleConfig = { emitDeparture() }

        val failure =
            assertFailsWith<IllegalStateException> {
                useCase(
                    1234,
                    DeviceProfile.Builder().also { wb -> wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig(enabled = true) }.build() }.build(),
                    User.Builder().build(),
                    isLocal = true,
                )
            }

        assertEquals("Device did not reconnect after the mqtt profile stage", failure.message)
        assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `full profile commits ordinary settings before transport disruptive stages`() = runTest(testDispatcher) {
        val importedChannels =
            listOf(
                ChannelSettings.Builder().also { wb ->
        wb.name = "Imported"
        wb.psk = byteArrayOf(1).toByteString()
    }.build(),
                ChannelSettings.Builder().also { wb ->
        wb.name = "Private"
        wb.psk = byteArrayOf(2).toByteString()
    }.build(),
            )
        val channelUrl =
            ChannelSet.Builder().also { wb ->
        wb.settings = importedChannels
        wb.lora_config = LoRaConfig(region = LoRaConfig.RegionCode.US)
    }.build()
                .getChannelUrl()
                .toString()
        radioConfigRepository.setChannelSet(
            ChannelSet.Builder().also { wb -> wb.settings = listOf(ChannelSettings.Builder().also { wb -> wb.name = "Old" }.build(), ChannelSettings.Builder().also { wb -> wb.name = "Stale" }.build()) }.build(),
        )
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868) }.build(),
        )
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        radioController.onStandaloneModuleConfig = {
            assertEquals(importedChannels, radioConfigRepository.currentChannelSet.settings)
            emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }

        useCase(1234, fullProfile(channelUrl), User.Builder().also { wb -> wb.long_name = "Old" }.build(), isLocal = true)

        assertFullProfileWriteOrder()
        assertFullProfileResult(importedChannels)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `transport stages retarget the current local identity after each reconnect`() = runTest(testDispatcher) {
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig.Builder().also { wb -> wb.security = SecurityConfig(private_key = ByteArray(32) { 7 }.toByteString()) }.build(),
        )
        radioController.onEditSettingsCommitted = {
            nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 5678))
            applyTransactionToDeviceState()
            emitRestartCycle()
        }
        radioController.onStandaloneModuleConfig = { moduleConfig ->
            val nextNodeNum = if (moduleConfig.mqtt != null) 9012 else 3456
            nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = nextNodeNum))
            emitRestartCycle()
        }
        radioController.onStandaloneConfig = { emitRestartCycle() }
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb ->
        wb.security = SecurityConfig(is_managed = true)
        wb.bluetooth = BluetoothConfig(enabled = true)
    }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = MQTTConfig(enabled = true)
        wb.serial = SerialConfig(enabled = true)
    }.build()
    }.build()

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(listOf(0), radioController.editSettingsDestinations)
        assertEquals(listOf(5678, 9012), radioController.moduleConfigDestinations.takeLast(2))
        assertEquals(3456, radioController.configWrites.last().destination)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `profile install rejects a stale local destination before writes`() = runTest(testDispatcher) {
        nodeRepository.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 5678))

        assertFailsWith<IllegalArgumentException> {
            useCase(1234, DeviceProfile.Builder().also { wb -> wb.long_name = "Stale" }.build(), User.Builder().build(), isLocal = true)
        }

        assertNoDeviceWrites()
    }

    @Test
    fun `bluetooth disable is the final stage and does not wait for an impossible reconnect`() =
        runTest(testDispatcher) {
            radioController.onEditSettingsCommitted = { commitAndRestart() }
            radioController.onStandaloneConfig = {
                radioController.setConnectionState(ConnectionState.Disconnected)
                yield()
            }
            val profile =
                DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb ->
        wb.device = DeviceConfig()
        wb.bluetooth = BluetoothConfig(enabled = false)
    }.build() }.build()

            val outcome = useCase(1234, profile, User.Builder().build(), isLocal = true)

            assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
            assertTrue(radioInterfaceService.disconnectCalled)
            assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
            assertEquals(BluetoothConfig(enabled = false), radioController.configWrites.last().config.bluetooth)
            assertFalse(restartTracker.restartExpected.value)
        }

    @Test
    fun `unchanged explicit profile LoRa still overrides channel URL LoRa`() = runTest(testDispatcher) {
        val currentLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 3)
        val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868, hop_limit = 5)
        val settings = listOf(ChannelSettings.Builder().also { wb ->
        wb.name = "Imported"
        wb.psk = byteArrayOf(1).toByteString()
    }.build())
        val channelUrl = ChannelSet.Builder().also { wb ->
        wb.settings = settings
        wb.lora_config = channelLora
    }.build().getChannelUrl().toString()
        radioConfigRepository.setLocalConfigDirect(LocalConfig.Builder().also { wb -> wb.lora = currentLora }.build())
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(
            1234,
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb -> wb.lora = currentLora }.build()
        wb.channel_url = channelUrl
    }.build(),
            User.Builder().build(),
            isLocal = true,
        )

        assertTrue(radioController.configWrites.none { it.config.lora != null })
        assertEquals(
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).toList(),
            radioController.localChannels.map(Channel::index),
        )
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `changed explicit profile LoRa is written exactly once when a channel URL is restored`() =
        runTest(testDispatcher) {
            val currentLora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868, hop_limit = 3)
            val profileLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 5)
            val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.ANZ, hop_limit = 2)
            val settings = listOf(ChannelSettings.Builder().also { wb ->
        wb.name = "Imported"
        wb.psk = byteArrayOf(1).toByteString()
    }.build())
            val channelUrl = ChannelSet.Builder().also { wb ->
        wb.settings = settings
        wb.lora_config = channelLora
    }.build().getChannelUrl().toString()
            radioConfigRepository.setLocalConfigDirect(LocalConfig.Builder().also { wb -> wb.lora = currentLora }.build())
            radioController.onEditSettingsCommitted = { commitAndRestart() }

            useCase(
                1234,
                DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb -> wb.lora = profileLora }.build()
        wb.channel_url = channelUrl
    }.build(),
                User.Builder().build(),
                isLocal = true,
            )

            assertEquals(listOf(profileLora), radioController.configWrites.mapNotNull { it.config.lora })
            assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
        }

    @Test
    fun `channel URL without profile LoRa restores its channel LoRa config`() = runTest(testDispatcher) {
        val channelLora = LoRaConfig(region = LoRaConfig.RegionCode.US, hop_limit = 4)
        val settings = listOf(ChannelSettings.Builder().also { wb ->
        wb.name = "Imported"
        wb.psk = byteArrayOf(1).toByteString()
    }.build())
        val channelUrl = ChannelSet.Builder().also { wb ->
        wb.settings = settings
        wb.lora_config = channelLora
    }.build().getChannelUrl().toString()
        val profile = DeviceProfile.Builder().also { wb -> wb.channel_url = channelUrl }.build()
        radioConfigRepository.setLocalConfigDirect(
            LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.EU_868) }.build(),
        )
        radioController.onEditSettingsCommitted = { commitAndRestart() }

        useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(channelLora, radioController.configWrites.single().config.lora)
        assertEquals(settings, radioConfigRepository.currentChannelSet.settings)
    }

    @Test
    fun `owner fields fail before writes when current owner is unavailable`() = runTest(testDispatcher) {
        val profile = DeviceProfile.Builder().also { wb -> wb.long_name = "Restored" }.build()

        assertFailsWith<IllegalArgumentException> { useCase(1234, profile, currentUser = null, isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `malformed channel URL fails before any device write`() = runTest(testDispatcher) {
        val profile = DeviceProfile.Builder().also { wb -> wb.channel_url = "https://example.com/not-a-channel" }.build()

        assertFailsWith<MalformedMeshtasticUrlException> { useCase(1234, profile, User.Builder().build(), isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `channel URL without payload fails before any device write`() = runTest(testDispatcher) {
        val profile = DeviceProfile.Builder().also { wb -> wb.channel_url = ChannelSet.Builder().build().getChannelUrl().toString() }.build()

        assertFailsWith<MalformedMeshtasticUrlException> { useCase(1234, profile, User.Builder().build(), isLocal = true) }

        assertNoDeviceWrites()
    }

    @Test
    fun `profile install rejects remote administration`() = runTest(testDispatcher) {
        assertFailsWith<IllegalArgumentException> {
            useCase(1234, DeviceProfile.Builder().also { wb -> wb.long_name = "Remote" }.build(), User.Builder().build(), isLocal = false)
        }

        assertNoDeviceWrites()
    }

    @Test
    fun `invoke installs is_unmessagable but never auto-installs is_licensed`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile.Builder().also { wb ->
        wb.is_unmessagable = true
        wb.is_licensed = true
    }.build()

        useCase(1234, profile, User.Builder().also { wb -> wb.long_name = "Old" }.build(), isLocal = true)

        assertEquals(true, radioController.lastSetOwnerUser?.is_unmessagable)
        assertEquals(false, radioController.lastSetOwnerUser?.is_licensed)
    }

    @Test
    fun `fixed position queue rejection aborts profile installation without committing cache`() =
        runTest(testDispatcher) {
            val rejection = PacketQueueRejectedException("Fixed position")
            radioController.onSetFixedPosition = { _, _ -> throw rejection }
            val profile = DeviceProfile.Builder().also { wb -> wb.fixed_position = org.meshtastic.proto.Position.Builder().also { wb ->
        wb.latitude_i = 1
        wb.longitude_i = 1
    }.build() }.build()

            assertFailsWith<PacketQueueRejectedException> { useCase(1234, profile, User.Builder().build(), isLocal = true) }

            assertTrue(radioController.localChannels.isEmpty())
            assertFalse(restartTracker.restartExpected.value)
        }

    @Test
    fun `crash-reverted transaction is retried once and fails before transport stages`() = runTest(testDispatcher) {
        // The device departs and comes back, but boots into its pre-transaction state: the interrupted save was
        // voided. Nothing is applied to the reported device state across the whole handshake.
        radioController.onEditSettingsCommitted = { emitRestartCycle() }
        val profile =
            DeviceProfile.Builder().also { wb ->
        wb.config = LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US) }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig(enabled = true) }.build()
    }.build()

        val failure = assertFailsWith<IllegalStateException> { useCase(1234, profile, User.Builder().build(), isLocal = true) }

        assertEquals("The device did not retain the restored device profile transaction", failure.message)
        // Exactly one bounded retry of the transaction stage, then an explicit failure.
        assertEquals(2, radioController.adminOperations.count { it == "begin" })
        // Transport-sensitive stages never run on the reverted device.
        assertTrue(radioController.moduleConfigWrites.none { it.config.mqtt != null })
        assertTrue(radioController.configWrites.none { it.config.network != null })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `retained transaction is reported installed without a retry`() = runTest(testDispatcher) {
        radioController.onEditSettingsCommitted = { commitAndRestart() }
        val profile = DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US) }.build() }.build()

        val outcome = useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(ProfileInstallOutcome.Completed, outcome)
        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertTrue(radioController.reboots.isEmpty())
        assertTrue(radioController.configWrites.any { it.config.lora?.region == LoRaConfig.RegionCode.US })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `healthy transaction requests an explicit reboot when commit does not reboot`() = runTest(testDispatcher) {
        // Restore-stack firmware saves at commit and only restarts when told to; without the explicit reboot the
        // stage would sit until the departure timeout.
        radioController.onEditSettingsCommitted = {}
        radioController.onRebooted = {
            applyTransactionToDeviceState()
            emitRestartCycle()
        }
        val profile = DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb -> wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US) }.build() }.build()

        val outcome = useCase(1234, profile, User.Builder().build(), isLocal = true)

        assertEquals(ProfileInstallOutcome.Completed, outcome)
        assertEquals(listOf(1234), radioController.reboots)
        assertEquals(1, radioController.adminOperations.count { it == "begin" })
        assertFalse(restartTracker.restartExpected.value)
    }

    @Test
    fun `intentional WiFi handoff over BLE stops automatic reconnection with a terminal outcome`() =
        runTest(testDispatcher) {
            // Enabling Wi-Fi on ESP32-classic firmware releases the Bluetooth controller at the next boot, so the
            // radio never advertises on this transport again. The installer must end the session deliberately
            // instead of leaving unbounded BLE retries running.
            radioController.onStandaloneConfig = { emitDeparture() }
            val profile =
                DeviceProfile.Builder().also { wb -> wb.config = LocalConfig.Builder().also { wb -> wb.network = NetworkConfig(wifi_enabled = true, wifi_ssid = "home") }.build() }.build()

            val outcome = useCase(1234, profile, User.Builder().build(), isLocal = true)

            assertEquals(ProfileInstallOutcome.TransportHandedOff, outcome)
            assertTrue(radioInterfaceService.disconnectCalled)
            assertFalse(radioController.editSettingsCalled)
            assertEquals(ConnectionState.Disconnected, radioController.connectionState.value)
            assertFalse(restartTracker.restartExpected.value)
        }

    private fun useCaseWith(repository: RadioConfigRepository) =
        InstallProfileUseCase(radioController, radioInterfaceService, repository, nodeRepository, restartTracker)

    private fun gatedConfigRepository(started: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        object : RadioConfigRepository by radioConfigRepository {
            override val localConfigFlow = flow {
                started.complete(Unit)
                release.await()
                emit(LocalConfig.Builder().build())
            }
        }

    private fun fullProfile(channelUrl: String) = DeviceProfile.Builder().also { wb ->
        wb.long_name = "Full Node"
        wb.short_name = "FULL"
        wb.channel_url = channelUrl
        wb.config = LocalConfig.Builder().also { wb ->
        wb.device = DeviceConfig()
        wb.position = PositionConfig()
        wb.power = PowerConfig()
        wb.network = NetworkConfig()
        wb.display = DisplayConfig()
        wb.lora = LoRaConfig(region = LoRaConfig.RegionCode.US)
        wb.bluetooth = BluetoothConfig(enabled = true)
        wb.security = SecurityConfig()
    }.build()
        wb.module_config = LocalModuleConfig.Builder().also { wb ->
        wb.mqtt = MQTTConfig(enabled = true)
        wb.serial = SerialConfig(enabled = true)
        wb.external_notification = ExternalNotificationConfig(enabled = true)
        wb.mesh_beacon = MeshBeaconConfig(broadcast_message = "restored beacon")
    }.build()
        wb.fixed_position = org.meshtastic.proto.Position.Builder().also { wb ->
        wb.latitude_i = 1
        wb.longitude_i = 2
    }.build()
    }.build()

    private fun assertFullProfileWriteOrder() {
        assertEquals(listOf("begin", "owner"), radioController.adminOperations.take(2))
        val commitIndex = radioController.adminOperations.indexOf("commit")
        assertTrue(commitIndex > radioController.adminOperations.indexOf("fixed-position"))
        val moduleOperationIndexes =
            radioController.adminOperations.mapIndexedNotNull { index, operation ->
                index.takeIf { operation.startsWith("module:") }
            }
        assertEquals(radioController.allModuleConfigs.size, moduleOperationIndexes.size)
        val firstTransportSensitiveModuleIndex =
            radioController.allModuleConfigs.indexOfFirst { it.mqtt != null || it.serial != null }
        assertTrue(firstTransportSensitiveModuleIndex > 0)
        assertTrue(moduleOperationIndexes.take(firstTransportSensitiveModuleIndex).all { it < commitIndex })
        assertTrue(moduleOperationIndexes.drop(firstTransportSensitiveModuleIndex).all { it > commitIndex })
    }

    private fun assertFullProfileResult(importedChannels: List<ChannelSettings>) {
        assertEquals(
            listOf(
                ExternalNotificationConfig(enabled = true),
                MeshBeaconConfig(broadcast_message = "restored beacon"),
                MQTTConfig(enabled = true),
                SerialConfig(enabled = true),
            ),
            radioController.allModuleConfigs.mapNotNull {
                it.external_notification ?: it.mesh_beacon ?: it.mqtt ?: it.serial
            },
        )
        assertEquals(
            BluetoothConfig(enabled = true),
            radioController.configWrites.mapNotNull { it.config.bluetooth }.last(),
        )
        assertEquals(NetworkConfig(), radioController.configWrites.mapNotNull { it.config.network }.last())
        assertEquals(1e-7, radioController.fixedPositions.single().latitude, absoluteTolerance = 1e-12)
        assertEquals(2e-7, radioController.fixedPositions.single().longitude, absoluteTolerance = 1e-12)
        assertEquals(
            (0 until CHANNEL_REPLACEMENT_SLOT_COUNT).toList(),
            radioController.localChannels.map(Channel::index),
        )
        assertEquals(importedChannels, radioConfigRepository.currentChannelSet.settings)
    }

    private fun assertNoDeviceWrites() {
        assertFalse(radioController.editSettingsCalled)
        assertTrue(radioController.adminOperations.isEmpty())
        assertTrue(radioController.configWrites.isEmpty())
        assertTrue(radioController.localChannels.isEmpty())
        assertTrue(radioController.allModuleConfigs.isEmpty())
        assertTrue(radioController.fixedPositions.isEmpty())
        assertEquals(null, radioController.lastSetOwnerUser)
    }

    /**
     * Models the device accepting the transaction: staged writes become the device state that the post-restart
     * handshake reports. Tests that leave the device state unchanged model a crash-reverted transaction instead.
     */
    private suspend fun applyTransactionToDeviceState() {
        var config = radioConfigRepository.currentLocalConfig
        radioController.configWrites
            .filter { it.destination == null }
            .forEach { write ->
                config =
                    config
                        .newBuilder()
                        .also { cfg ->
                            cfg.device = write.config.device ?: config.device
                            cfg.position = write.config.position ?: config.position
                            cfg.power = write.config.power ?: config.power
                            cfg.network = write.config.network ?: config.network
                            cfg.display = write.config.display ?: config.display
                            cfg.lora = write.config.lora ?: config.lora
                            cfg.bluetooth = write.config.bluetooth ?: config.bluetooth
                            cfg.security = write.config.security ?: config.security
                        }
                        .build()
            }
        radioConfigRepository.setLocalConfigDirect(config)
        var moduleConfig = radioConfigRepository.currentModuleConfig
        radioController.moduleConfigWrites
            .filter { it.destination == null }
            .forEach { write -> moduleConfig = moduleConfig.updatedWithModuleConfig(write.config) }
        radioConfigRepository.setLocalModuleConfigDirect(moduleConfig)
        radioController.localChannels.forEach { channel -> radioConfigRepository.updateChannelSettings(channel) }
        radioController.ownerWrites
            .lastOrNull()
            ?.takeIf { it.destination == null }
            ?.let { write ->
                nodeRepository.myNodeInfo.value?.myNodeNum?.let { num ->
                    nodeRepository.upsert(Node(num = num, user = write.user))
                }
            }
    }

    /** A transaction the device accepted: commit, state application, restart, and a fresh handshake. */
    private suspend fun commitAndRestart() {
        applyTransactionToDeviceState()
        emitRestartCycle()
    }

    private suspend fun emitRestartCycle() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        yield()
        radioController.setConnectionState(ConnectionState.Connecting)
        yield()
        radioController.setConnectionState(ConnectionState.Connected)
        yield()
    }

    private fun emitFastRestartCycle() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        radioController.setConnectionState(ConnectionState.Connecting)
        radioController.setConnectionState(ConnectionState.Connected)
    }

    private suspend fun emitDeparture() {
        radioController.setConnectionState(ConnectionState.Disconnected)
        yield()
    }
}

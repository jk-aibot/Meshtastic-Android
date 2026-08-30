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

import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig

/** Exhaustive registry for every installable [ModuleConfig] one-of arm. */
internal enum class ModuleConfigField(val transportSensitive: Boolean = false) {
    MQTT(transportSensitive = true),
    SERIAL(transportSensitive = true),
    EXTERNAL_NOTIFICATION,
    STORE_FORWARD,
    RANGE_TEST,
    TELEMETRY,
    CANNED_MESSAGE,
    AUDIO,
    REMOTE_HARDWARE,
    NEIGHBOR_INFO,
    AMBIENT_LIGHTING,
    DETECTION_SENSOR,
    PAXCOUNTER,
    STATUSMESSAGE,
    TRAFFIC_MANAGEMENT,
    TAK,
    MESH_BEACON,
    ;

    @Suppress("CyclomaticComplexMethod")
    fun extract(config: LocalModuleConfig): ModuleConfig? = when (this) {
        MQTT -> config.mqtt?.let { ModuleConfig.Builder().also { wb -> wb.mqtt = it }.build() }
        SERIAL -> config.serial?.let { ModuleConfig.Builder().also { wb -> wb.serial = it }.build() }
        EXTERNAL_NOTIFICATION -> config.external_notification?.let { ModuleConfig.Builder().also { wb -> wb.external_notification = it }.build() }
        STORE_FORWARD -> config.store_forward?.let { ModuleConfig.Builder().also { wb -> wb.store_forward = it }.build() }
        RANGE_TEST -> config.range_test?.let { ModuleConfig.Builder().also { wb -> wb.range_test = it }.build() }
        TELEMETRY -> config.telemetry?.let { ModuleConfig.Builder().also { wb -> wb.telemetry = it }.build() }
        CANNED_MESSAGE -> config.canned_message?.let { ModuleConfig.Builder().also { wb -> wb.canned_message = it }.build() }
        AUDIO -> config.audio?.let { ModuleConfig.Builder().also { wb -> wb.audio = it }.build() }
        REMOTE_HARDWARE -> config.remote_hardware?.let { ModuleConfig.Builder().also { wb -> wb.remote_hardware = it }.build() }
        NEIGHBOR_INFO -> config.neighbor_info?.let { ModuleConfig.Builder().also { wb -> wb.neighbor_info = it }.build() }
        AMBIENT_LIGHTING -> config.ambient_lighting?.let { ModuleConfig.Builder().also { wb -> wb.ambient_lighting = it }.build() }
        DETECTION_SENSOR -> config.detection_sensor?.let { ModuleConfig.Builder().also { wb -> wb.detection_sensor = it }.build() }
        PAXCOUNTER -> config.paxcounter?.let { ModuleConfig.Builder().also { wb -> wb.paxcounter = it }.build() }
        STATUSMESSAGE -> config.statusmessage?.let { ModuleConfig.Builder().also { wb -> wb.statusmessage = it }.build() }
        TRAFFIC_MANAGEMENT -> config.traffic_management?.let { ModuleConfig.Builder().also { wb -> wb.traffic_management = it }.build() }
        TAK -> config.tak?.let { ModuleConfig.Builder().also { wb -> wb.tak = it }.build() }
        MESH_BEACON -> config.mesh_beacon?.let { ModuleConfig.Builder().also { wb -> wb.mesh_beacon = it }.build() }
    }

    @Suppress("CyclomaticComplexMethod")
    fun merge(target: LocalModuleConfig, config: ModuleConfig): LocalModuleConfig = when (this) {
        MQTT -> config.mqtt?.let { target.newBuilder().also { wb -> wb.mqtt = it }.build() } ?: target

        SERIAL -> config.serial?.let { target.newBuilder().also { wb -> wb.serial = it }.build() } ?: target

        EXTERNAL_NOTIFICATION ->
            config.external_notification?.let { target.newBuilder().also { wb -> wb.external_notification = it }.build() } ?: target

        STORE_FORWARD -> config.store_forward?.let { target.newBuilder().also { wb -> wb.store_forward = it }.build() } ?: target

        RANGE_TEST -> config.range_test?.let { target.newBuilder().also { wb -> wb.range_test = it }.build() } ?: target

        TELEMETRY -> config.telemetry?.let { target.newBuilder().also { wb -> wb.telemetry = it }.build() } ?: target

        CANNED_MESSAGE -> config.canned_message?.let { target.newBuilder().also { wb -> wb.canned_message = it }.build() } ?: target

        AUDIO -> config.audio?.let { target.newBuilder().also { wb -> wb.audio = it }.build() } ?: target

        REMOTE_HARDWARE -> config.remote_hardware?.let { target.newBuilder().also { wb -> wb.remote_hardware = it }.build() } ?: target

        NEIGHBOR_INFO -> config.neighbor_info?.let { target.newBuilder().also { wb -> wb.neighbor_info = it }.build() } ?: target

        AMBIENT_LIGHTING -> config.ambient_lighting?.let { target.newBuilder().also { wb -> wb.ambient_lighting = it }.build() } ?: target

        DETECTION_SENSOR -> config.detection_sensor?.let { target.newBuilder().also { wb -> wb.detection_sensor = it }.build() } ?: target

        PAXCOUNTER -> config.paxcounter?.let { target.newBuilder().also { wb -> wb.paxcounter = it }.build() } ?: target

        STATUSMESSAGE -> config.statusmessage?.let { target.newBuilder().also { wb -> wb.statusmessage = it }.build() } ?: target

        TRAFFIC_MANAGEMENT -> config.traffic_management?.let { target.newBuilder().also { wb -> wb.traffic_management = it }.build() } ?: target

        TAK -> config.tak?.let { target.newBuilder().also { wb -> wb.tak = it }.build() } ?: target

        MESH_BEACON -> config.mesh_beacon?.let { target.newBuilder().also { wb -> wb.mesh_beacon = it }.build() } ?: target
    }
}

internal fun LocalModuleConfig.moduleConfigs(includeTransportSensitive: Boolean = false): List<ModuleConfig> =
    ModuleConfigField.entries
        .asSequence()
        .filter { includeTransportSensitive || !it.transportSensitive }
        .mapNotNull { it.extract(this) }
        .toList()

internal fun LocalModuleConfig.withoutUnchangedModuleFields(current: LocalModuleConfig): LocalModuleConfig? {
    val changed =
        ModuleConfigField.entries.mapNotNull { field ->
            field.extract(this)?.takeUnless { it == field.extract(current) }
        }
    return changed
        .fold(LocalModuleConfig.Builder().build()) { result, config -> result.updatedWithModuleConfig(config) }
        .takeIf { changed.isNotEmpty() }
}

/**
 * Applies every populated [ModuleConfig] arm to this aggregate using the same exhaustive registry as profile restore.
 */
internal fun LocalModuleConfig.updatedWithModuleConfig(config: ModuleConfig): LocalModuleConfig =
    ModuleConfigField.entries.fold(this) { result, field -> field.merge(result, config) }

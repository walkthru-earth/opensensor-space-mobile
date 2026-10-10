package earth.walkthru.opensensor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.PmsStatus
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.aqsync.units.formatFixed
import earth.walkthru.aqsync.units.formatPm
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.CardShape
import earth.walkthru.opensensor.device.deviceSettingsLabel
import earth.walkthru.opensensor.permissions.PermissionFlow
import earth.walkthru.opensensor.permissions.SettingsLink
import earth.walkthru.opensensor.strings.resolve
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.action_back
import earth.walkthru.opensensor.strings.resources.action_cancel
import earth.walkthru.opensensor.strings.resources.app_name
import earth.walkthru.opensensor.strings.resources.batch_value
import earth.walkthru.opensensor.strings.resources.companion_connect
import earth.walkthru.opensensor.strings.resources.companion_files_finalized
import earth.walkthru.opensensor.strings.resources.companion_firmware
import earth.walkthru.opensensor.strings.resources.companion_lan_bytes
import earth.walkthru.opensensor.strings.resources.companion_lan_connect_label
import earth.walkthru.opensensor.strings.resources.companion_lan_discovered
import earth.walkthru.opensensor.strings.resources.companion_lan_files
import earth.walkthru.opensensor.strings.resources.companion_lan_find
import earth.walkthru.opensensor.strings.resources.companion_lan_finding
import earth.walkthru.opensensor.strings.resources.companion_lan_link
import earth.walkthru.opensensor.strings.resources.companion_lan_needs_access
import earth.walkthru.opensensor.strings.resources.companion_lan_paused
import earth.walkthru.opensensor.strings.resources.companion_lan_rate
import earth.walkthru.opensensor.strings.resources.companion_lan_title
import earth.walkthru.opensensor.strings.resources.companion_now
import earth.walkthru.opensensor.strings.resources.companion_pm25_mood
import earth.walkthru.opensensor.strings.resources.companion_storage_degraded
import earth.walkthru.opensensor.strings.resources.companion_storage_error
import earth.walkthru.opensensor.strings.resources.companion_storage_full
import earth.walkthru.opensensor.strings.resources.companion_sync
import earth.walkthru.opensensor.strings.resources.companion_wifi_access_save
import earth.walkthru.opensensor.strings.resources.companion_wifi_access_saving
import earth.walkthru.opensensor.strings.resources.conn_connecting
import earth.walkthru.opensensor.strings.resources.conn_disconnected
import earth.walkthru.opensensor.strings.resources.conn_ready
import earth.walkthru.opensensor.strings.resources.nav_debug
import earth.walkthru.opensensor.strings.resources.nav_disconnect
import earth.walkthru.opensensor.strings.resources.now_waiting
import earth.walkthru.opensensor.strings.resources.perm_open_settings
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

/*
 * The device page, opened from My devices (`:feature:devices`) as the Today entry. It shows the
 * open Bluetooth link (Now card, sync, Wi-Fi access, disconnect), the Wi-Fi sessions and export.
 * It renders inside the brand theme that AppRoot applies (OpenSensorTheme), so it reads colours and shapes from MaterialTheme and never sets a theme of its own. The curves
 * match the floating control rule through the brand shapes, small (12 dp) for buttons and the
 * design system's [CardShape] (32 dp, the same as the Android BrandCard) for cards. Cards sit on
 * the brand surface with a hairline, buttons are curved cards, not pills.
 *
 * Detail level. iOS shows the Simple level for now (docs/screens.md, Detail levels). Signal dBm,
 * firmware, finalized file counts, byte counters and transfer rate stay behind [showTechnical]
 * until the shared detail level setting lands.
 */

@Composable
internal fun CompanionScreen(state: CompanionState, title: String?, onBack: () -> Unit, reconnect: (() -> Unit)?, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, syncBluetooth: () -> Unit, selectedBluetooth: Boolean = false, selectedStation: String? = null, activeDevice: String? = null, units: UnitSystem = UnitSystem.METRIC, showTechnical: Boolean = false, onOpenDebug: ((String) -> Unit)? = null, onOpenSettings: (() -> Unit)? = null, autoSync: (@Composable () -> Unit)? = null) {
    // Display units from Settings. Readings stay SI everywhere else.
    val formatter = UnitFormatter(units)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.statusBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back)) }
                    Text(title ?: stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                }
            }
            // The link notice changes while the user waits, so VoiceOver announces it without moving focus.
            if (selectedBluetooth && !state.message.isEmpty) item { Text(state.message.resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            // Shown only after iOS reports a Bluetooth denial, which first needs a Find sensors tap.
            if (PermissionFlow.offersSettings(state.bluetoothAccess)) {
                item { SettingsLink(stringResource(Res.string.perm_open_settings)) }
            }
            if (selectedBluetooth) {
                item {
                    BrandCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(Res.string.companion_now), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            val sample = state.live
                            val profile = state.profile
                            // As on Android Today, a board without a particle sensor leads with its temperature.
                            val particles = sample?.pms != null && sample.pms != PmsStatus.ABSENT.code
                            if (particles || profile == null || sample == null) {
                                when (val reading = state.reading) {
                                    // The value and its mood read as one element, with the card's 12 dp gap kept.
                                    is NowReading.Reading -> Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(Res.string.batch_value, formatPm(reading.pm25)), style = MaterialTheme.typography.displaySmall)
                                        Text(stringResource(Res.string.companion_pm25_mood, reading.mood.title))
                                    }
                                    is NowReading.SensorNotReady -> Text(reading.hint)
                                    NowReading.Waiting -> Text(stringResource(Res.string.now_waiting))
                                }
                            }
                            if (profile != null && sample != null) {
                                // Temperature shows whenever present, whatever the particle state. Board
                                // temperature beside particles is a technical detail, as on Android.
                                val ambient = profile.temperature == TemperatureSource.AMBIENT
                                if (ambient || !particles || showTechnical) {
                                    sample.t?.takeIf { it.isFinite() }?.let { t ->
                                        val metric = if (ambient) Metrics.AMBIENT_TEMP else Metrics.BOARD_TEMP
                                        Text(listOf(metric.label, number(formatter.value(t, metric.unit)), formatter.unit(metric.unit)).joinToString(" "))
                                    }
                                }
                                if (profile.humidity) sample.rh?.takeIf { it.isFinite() }?.let { Text(listOf(Metrics.HUMIDITY.label, number(it), Metrics.HUMIDITY.unit).joinToString(" ")) }
                            }
                            state.status?.let { status ->
                                if (status.storageFailed) Text(stringResource(Res.string.companion_storage_error), color = MaterialTheme.colorScheme.error)
                                if (status.sdFull || status.sdDegraded) {
                                    Text(stringResource(if (status.sdFull) Res.string.companion_storage_full else Res.string.companion_storage_degraded), color = MaterialTheme.colorScheme.error)
                                }
                                if (showTechnical) status.fin?.let { Text(pluralStringResource(Res.plurals.companion_files_finalized, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it), color = muted) }
                            }
                            if (showTechnical) state.info?.fw?.let { Text(stringResource(Res.string.companion_firmware, it), style = MaterialTheme.typography.bodySmall, color = muted) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.syncAvailable) {
                            // Uses the Wi-Fi session instead when this sensor has one open, as Android prefers LAN.
                            Button(onClick = syncBluetooth, enabled = !state.syncBusy, shape = MaterialTheme.shapes.small) {
                                Text(stringResource(Res.string.companion_sync))
                            }
                            if (state.syncBusy) {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                                    strokeCap = StrokeCap.Round,
                                )
                            }
                            state.syncProgress?.takeIf { it.filesTotal > 0 }?.let { p ->
                                // Sync progress in words, announced as files complete.
                                Text(
                                    pluralStringResource(Res.plurals.companion_lan_files, p.filesTotal, p.filesDone, p.filesTotal),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                if (showTechnical && p.bytesPerSecond > 0.0) Text(stringResource(Res.string.companion_lan_rate, number(p.bytesPerSecond / 1024)), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            if (!state.syncMessage.isEmpty) Text(state.syncMessage.resolve(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (state.wifiAccessAvailable) {
                            OutlinedButton(onClick = saveWifiAccess, enabled = !state.wifiAccessBusy, shape = MaterialTheme.shapes.small) {
                                Text(stringResource(if (state.wifiAccessBusy) Res.string.companion_wifi_access_saving else Res.string.companion_wifi_access_save))
                            }
                            Text(state.wifiAccessMessage.resolve(), style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                item { OutlinedButton(onClick = disconnect, shape = MaterialTheme.shapes.small) { Text(stringResource(if (state.syncBusy) Res.string.action_cancel else Res.string.nav_disconnect)) } }
            } else if (reconnect != null && activeDevice == null) {
                // The link is closed. Bluetooth is allowed here, since My devices asked before opening.
                item { Button(onClick = reconnect, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.companion_connect)) } }
            }
            if (activeDevice != null) {
                if (onOpenSettings != null) {
                    item { OutlinedButton(onClick = onOpenSettings, shape = MaterialTheme.shapes.small) { Text(deviceSettingsLabel()) } }
                }
                if (onOpenDebug != null) {
                    item { OutlinedButton(onClick = { onOpenDebug(activeDevice) }, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.nav_debug)) } }
                }
            }
            // Auto-sync for a known sensor (#23). The caller passes it only for a known peripheral.
            if (autoSync != null) item { BrandCard { autoSync() } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.companion_lan_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (!state.lanMessage.isEmpty) {
                        Text(
                            state.lanMessage.resolve(),
                            color = if (state.lanAccessDenied) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                    if (PermissionFlow.offersSettings(state.lanAccess)) SettingsLink(stringResource(Res.string.perm_open_settings))
                    OutlinedButton(onClick = scanLan, enabled = !state.lanScanning, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(if (state.lanScanning) Res.string.companion_lan_finding else Res.string.companion_lan_find))
                    }
                }
            }
            items(
                (state.lanDevices + state.lanSessions.keys).distinct().filter { name ->
                    selectedStation != null && (state.lanInfo[name]?.station == selectedStation || state.lanStations[name] == selectedStation)
                }.sorted(),
                key = { it }
            ) { name ->
                val session = state.lanSessions[name]
                val paused = name in state.lanPaused
                val canConnect = session?.connected != true && session?.busy != true && name in state.lanDevices
                val connectLabel = stringResource(Res.string.companion_lan_connect_label, name)
                BrandCard(
                    Modifier.then(
                        if (canConnect) Modifier.clickable(onClickLabel = connectLabel, role = Role.Button) { connectLan(name) } else Modifier,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        val connectionState = when {
                            paused -> Res.string.companion_lan_paused
                            session?.connected == true -> Res.string.conn_ready
                            session?.busy == true -> Res.string.conn_connecting
                            session == null -> Res.string.companion_lan_discovered
                            else -> Res.string.conn_disconnected
                        }
                        Text(stringResource(Res.string.companion_lan_link, stringResource(connectionState)), style = MaterialTheme.typography.labelLarge, color = muted)
                        if (session?.busy == true) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                                strokeCap = StrokeCap.Round,
                            )
                        }
                        if (session?.connected == true) {
                            when (val reading = state.lanReadings[name] ?: NowReading.Waiting) {
                                is NowReading.Reading -> {
                                    Text(stringResource(Res.string.batch_value, formatPm(reading.pm25)), style = MaterialTheme.typography.headlineMedium)
                                    Text(stringResource(Res.string.companion_pm25_mood, reading.mood.title))
                                }
                                is NowReading.SensorNotReady -> Text(reading.hint)
                                NowReading.Waiting -> Text(stringResource(Res.string.now_waiting))
                            }
                        }
                        Text(session?.message?.resolve() ?: stringResource(Res.string.companion_lan_needs_access))
                        if (session != null && session.filesTotal > 0) {
                            Text(pluralStringResource(Res.plurals.companion_lan_files, session.filesTotal, session.filesDone, session.filesTotal), style = MaterialTheme.typography.bodySmall, color = muted)
                            if (showTechnical) {
                                Text(stringResource(Res.string.companion_lan_bytes, session.bytesDone.toString()), style = MaterialTheme.typography.bodySmall, color = muted)
                                if (session.bytesPerSecond > 0.0) Text(stringResource(Res.string.companion_lan_rate, number(session.bytesPerSecond / 1024)), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (session?.connected == true) {
                                Button(onClick = { syncLan(name) }, enabled = !session.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.companion_sync)) }
                                OutlinedButton(onClick = { disconnectLan(name) }, shape = MaterialTheme.shapes.small) { Text(stringResource(if (session.busy) Res.string.action_cancel else Res.string.nav_disconnect)) }
                            } else {
                                Button(onClick = { connectLan(name) }, enabled = canConnect, shape = MaterialTheme.shapes.small) {
                                    Text(stringResource(if (session?.busy == true) Res.string.conn_connecting else Res.string.companion_connect))
                                }
                                if (session?.busy == true || paused) {
                                    OutlinedButton(onClick = { disconnectLan(name) }, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.action_cancel)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A content card on the brand surface with a hairline border, curved like the website's cards. */
@Composable
private fun BrandCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

/** Temperature, humidity and rates, as Android `fmt` prints them. PM values go through [formatPm]. */
private fun number(value: Double): String =
    if (value == floor(value) && value < 1000) formatFixed(value, 0) else formatFixed(value, 1)

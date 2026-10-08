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
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import kotlin.math.round

/*
 * The My devices tab. It renders inside the brand theme that AppRoot applies (OpenSensorTheme), so
 * it reads colours and shapes from MaterialTheme and never sets a theme of its own. This file is
 * commonMain, which also compiles for the JVM, so it cannot use :ui:designsystem directly. The
 * curves match the floating control rule through the brand shapes, small (12 dp) for buttons and
 * medium for cards. Cards sit on the brand surface with a hairline, buttons are curved cards, not
 * pills.
 */

@Composable
internal fun CompanionScreen(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit, units: UnitSystem = UnitSystem.METRIC) {
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
                Column {
                    Text("opensensor.space", style = MaterialTheme.typography.headlineMedium)
                    Text("Your sensors, nearby", style = MaterialTheme.typography.titleMedium, color = muted)
                }
            }
            if (state.message.isNotEmpty()) item { Text(state.message, style = MaterialTheme.typography.bodyLarge) }
            if (state.selectedId != null) {
                item {
                    BrandCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Now", style = MaterialTheme.typography.titleLarge)
                            when (val reading = state.reading) {
                                is NowReading.Reading -> {
                                    Text("${number(reading.pm25)} µg/m³", style = MaterialTheme.typography.displaySmall)
                                    Text("PM2.5 · ${reading.mood.title}")
                                }
                                is NowReading.SensorNotReady -> Text(reading.hint)
                                NowReading.Waiting -> Text("Waiting for a particle reading…")
                            }
                            val sample = state.live
                            val profile = state.profile
                            if (profile != null && sample?.pmValid == true) {
                                sample.t?.takeIf { it.isFinite() }?.let { t ->
                                    val metric = if (profile.temperature == TemperatureSource.AMBIENT) Metrics.AMBIENT_TEMP else Metrics.BOARD_TEMP
                                    Text("${profile.temperature.label} ${number(formatter.value(t, metric.unit))} ${formatter.unit(metric.unit)}")
                                }
                                if (profile.humidity) sample.rh?.takeIf { it.isFinite() }?.let { Text("Humidity ${number(it)} %") }
                            }
                            state.status?.let { status ->
                                if (status.storageFailed) Text("The sensor reports a storage error.", color = MaterialTheme.colorScheme.error)
                                status.fin?.let { Text("$it files finalized on the sensor", color = muted) }
                            }
                            state.info?.fw?.let { Text("Firmware $it", style = MaterialTheme.typography.bodySmall, color = muted) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.wifiAccessAvailable) {
                            OutlinedButton(onClick = saveWifiAccess, enabled = !state.wifiAccessBusy, shape = MaterialTheme.shapes.small) {
                                Text(if (state.wifiAccessBusy) "Saving access…" else "Save Wi-Fi access")
                            }
                            Text(state.wifiAccessMessage, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                item { OutlinedButton(onClick = disconnect, shape = MaterialTheme.shapes.small) { Text("Disconnect") } }
            } else {
                item {
                    // Tapping creates the Bluetooth manager, so iOS asks for Bluetooth here and not at launch.
                    Button(onClick = scan, enabled = !state.scanning, shape = MaterialTheme.shapes.small) {
                        Text(if (state.scanning) "Scanning…" else "Find sensors")
                    }
                }
                items(state.devices, key = { it.id }) { device ->
                    BrandCard {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            Button(onClick = { connect(device.id) }, shape = MaterialTheme.shapes.small) { Text("Connect") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text("On your Wi-Fi", style = MaterialTheme.typography.titleLarge)
                    if (state.lanMessage.isNotEmpty()) {
                        Text(
                            state.lanMessage,
                            color = if (state.lanAccessDenied) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                    OutlinedButton(onClick = scanLan, enabled = !state.lanScanning, shape = MaterialTheme.shapes.small) {
                        Text(if (state.lanScanning) "Looking on Wi-Fi…" else "Find Wi-Fi sensors")
                    }
                }
            }
            items((state.lanDevices + state.lanSessions.keys).distinct().sorted(), key = { it }) { name ->
                val session = state.lanSessions[name]
                val paused = name in state.lanPaused
                val canConnect = session?.connected != true && session?.busy != true && name in state.lanDevices
                BrandCard(
                    Modifier.then(
                        if (canConnect) Modifier.clickable(onClickLabel = "Connect to $name over Wi-Fi") { connectLan(name) } else Modifier,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        val connectionLabel = when {
                            paused -> "Wi-Fi · Sync paused"
                            session?.connected == true -> "Wi-Fi · Connected"
                            session?.busy == true -> "Wi-Fi · Connecting…"
                            session == null -> "Wi-Fi · Discovered"
                            else -> "Wi-Fi · Not connected"
                        }
                        Text(connectionLabel, style = MaterialTheme.typography.labelLarge, color = muted)
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
                                    Text("${number(reading.pm25)} µg/m³", style = MaterialTheme.typography.headlineMedium)
                                    Text("PM2.5 · ${reading.mood.title}")
                                }
                                is NowReading.SensorNotReady -> Text(reading.hint)
                                NowReading.Waiting -> Text("Waiting for a fresh live reading…")
                            }
                        }
                        Text(session?.message ?: "Save Wi-Fi access over Bluetooth first.")
                        if (session != null && session.filesTotal > 0) {
                            Text("${session.filesDone}/${session.filesTotal} files · ${session.bytesDone} bytes in current file", style = MaterialTheme.typography.bodySmall, color = muted)
                            if (session.bytesPerSecond > 0.0) Text("${number(session.bytesPerSecond / 1024)} KiB/s average", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (session?.connected == true) {
                                Button(onClick = { syncLan(name) }, enabled = !session.busy, shape = MaterialTheme.shapes.small) { Text("Sync") }
                                OutlinedButton(onClick = { disconnectLan(name) }, shape = MaterialTheme.shapes.small) { Text(if (session.busy) "Cancel" else "Disconnect") }
                            } else {
                                Button(onClick = { connectLan(name) }, enabled = canConnect, shape = MaterialTheme.shapes.small) {
                                    Text(if (session?.busy == true) "Connecting…" else "Connect")
                                }
                                if (session?.busy == true || paused) {
                                    OutlinedButton(onClick = { disconnectLan(name) }, shape = MaterialTheme.shapes.small) { Text("Cancel") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = exportArchive, enabled = !state.exportBusy, shape = MaterialTheme.shapes.small) {
                        Text(if (state.exportBusy) "Preparing export…" else "Export Parquet files")
                    }
                    if (state.exportMessage.isNotEmpty()) Text(state.exportMessage, style = MaterialTheme.typography.bodySmall, color = muted)
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

private fun number(value: Double): String = (round(value * 10) / 10).toString()

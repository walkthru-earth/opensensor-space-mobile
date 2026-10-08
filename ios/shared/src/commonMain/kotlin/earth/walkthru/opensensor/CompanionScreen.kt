package earth.walkthru.opensensor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.NowReading
import kotlin.math.round

@Composable
internal fun CompanionScreen(state: CompanionState, scan: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit, scanLan: () -> Unit, saveWifiAccess: () -> Unit, connectLan: (String) -> Unit, syncLan: (String) -> Unit, disconnectLan: (String) -> Unit, exportArchive: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 64.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text("opensensor.space", style = MaterialTheme.typography.headlineLarge)
                    Text("Your sensors, nearby", style = MaterialTheme.typography.titleMedium)
                }
                item { Text(state.message, style = MaterialTheme.typography.bodyLarge) }
                if (state.selectedId != null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    sample.t?.takeIf { it.isFinite() }?.let { Text("${profile.temperature.label}: ${number(it)} °C") }
                                    if (profile.humidity) sample.rh?.takeIf { it.isFinite() }?.let { Text("Humidity: ${number(it)} %") }
                                }
                                state.status?.let { status ->
                                    if (status.storageFailed) Text("The sensor reports a storage error.")
                                    status.fin?.let { Text("$it files finalized on the sensor") }
                                }
                                state.info?.fw?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.wifiAccessAvailable) {
                                OutlinedButton(onClick = saveWifiAccess, enabled = !state.wifiAccessBusy) {
                                    Text(if (state.wifiAccessBusy) "Saving access…" else "Save Wi-Fi access")
                                }
                                Text(state.wifiAccessMessage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { OutlinedButton(onClick = disconnect) { Text("Disconnect") } }
                } else {
                    item {
                        Button(onClick = scan, enabled = state.bluetoothAvailable && !state.scanning) {
                            Text(if (state.scanning) "Scanning…" else "Find sensors")
                        }
                    }
                    items(state.devices, key = { it.id }) { device ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { connect(device.id) }) { Text("Connect") }
                            }
                        }
                    }
                }
                item {
                    Text("On your Wi-Fi", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.lanMessage,
                        color = if (state.lanAccessDenied) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    OutlinedButton(onClick = scanLan, enabled = !state.lanScanning) {
                        Text(if (state.lanScanning) "Looking on Wi-Fi…" else "Find Wi-Fi sensors")
                    }
                }
                items((state.lanDevices + state.lanSessions.keys).distinct().sorted(), key = { it }) { name ->
                    val session = state.lanSessions[name]
                    val paused = name in state.lanPaused
                    val canConnect = session?.connected != true && session?.busy != true && name in state.lanDevices
                    Card(
                        Modifier.fillMaxWidth().then(
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
                            Text(connectionLabel, style = MaterialTheme.typography.labelLarge)
                            if (session?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                            Text(session?.message ?: "Found on your local network. Save Wi-Fi access over Bluetooth first.")
                            if (session != null && session.filesTotal > 0) {
                                Text("${session.filesDone}/${session.filesTotal} files · ${session.bytesDone} bytes in current file")
                                if (session.bytesPerSecond > 0.0) Text("${number(session.bytesPerSecond / 1024)} KiB/s average")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (session?.connected == true) {
                                    Button(onClick = { syncLan(name) }, enabled = !session.busy) { Text("Sync") }
                                    OutlinedButton(onClick = { disconnectLan(name) }) { Text(if (session.busy) "Cancel" else "Disconnect") }
                                } else {
                                    Button(onClick = { connectLan(name) }, enabled = canConnect) {
                                        Text(if (session?.busy == true) "Connecting…" else "Connect")
                                    }
                                    if (session?.busy == true || paused) {
                                        OutlinedButton(onClick = { disconnectLan(name) }) { Text("Cancel") }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = exportArchive, enabled = !state.exportBusy) {
                            Text(if (state.exportBusy) "Preparing export…" else "Export Parquet files")
                        }
                        Text("Export verified files saved on this phone, even while offline. Choose a destination in Files to save the archive.", style = MaterialTheme.typography.bodySmall)
                        if (state.exportMessage.isNotEmpty()) Text(state.exportMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("Pair over Bluetooth once per phone and save Wi-Fi access. Connect sensors on the same Wi-Fi to collect their Parquet files. Two sensors can sync at once. Sync continues for the background time iOS allows, pauses when necessary, and resumes when you return. Downloaded bytes stay on this phone. Archived history charts are not available yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun number(value: Double): String = (round(value * 10) / 10).toString()

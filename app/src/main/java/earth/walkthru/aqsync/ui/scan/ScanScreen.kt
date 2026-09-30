package earth.walkthru.aqsync.ui.scan

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ble.ScannedDevice
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.rememberNowMillis
import earth.walkthru.aqsync.ui.device.MoodFace
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission
import earth.walkthru.aqsync.ui.theme.color
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun ScanScreen(
    onConnected: (address: String) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var forgetTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    // Scan while the screen is visible; the radio is not free.
    LifecycleStartEffect(Unit) {
        viewModel.startScan()
        onStopOrDispose { viewModel.stopScan() }
    }

    // The collect-all run shows a dataSync notification (API 33+ needs POST_NOTIFICATIONS); a denial only hides it.
    val notificationPermission = rememberNotificationPermission()
    fun withNotificationPermission(action: () -> Unit) = notificationPermission.ensureThen(action)

    // Detail owns persistent link/error state and remains useful with only the local archive.
    fun openDevice(address: String, name: String?) {
        viewModel.connect(address, name)
        onConnected(address)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.showFakeEntry) {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_fake)) },
                                leadingIcon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                                onClick = { open = false; viewModel.connectFake(); onConnected(FakeTransport.FAKE_ADDRESS) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_fake_lan)) },
                                leadingIcon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                                onClick = { open = false; viewModel.connectFakeLan(); onConnected(FakeTransport.FAKE_LAN_ADDRESS) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.scanning) viewModel.stopScan() else viewModel.startScan() },
                icon = { Icon(if (state.scanning) Icons.Rounded.Stop else Icons.Rounded.Radar, contentDescription = null) },
                text = { Text(if (state.scanning) "Stop" else "Scan") },
            )
        },
    ) { padding ->
        val now = rememberNowMillis()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.activeDevices.isNotEmpty()) {
                item(key = "active-devices") { Eyebrow("device connections · ${state.activeDevices.size}") }
                items(state.activeDevices, key = { "active:" + it.address }) { active ->
                    Column(Modifier.fillMaxWidth().clickable { openDevice(active.address, active.name) }.padding(vertical = 8.dp)) {
                        Text(active.name ?: active.address, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        ConnectionBanner(active.connection, reconnecting = active.reconnect)
                        if (active.progress.running) {
                            Text("Collecting ${active.progress.filesDone}/${active.progress.filesTotal} files", style = MaterialTheme.typography.bodySmall)
                        } else if (active.backgroundOwner != null) {
                            Text("Background collection in progress", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(visible = state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.scanError?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Text(err, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            item { Eyebrow("nearby · ${state.nearby.size}") }
            if (state.nearby.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.scan_pair_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.nearby, key = { "n:" + it.address }) { dev ->
                val known = state.known.firstOrNull { it.address == dev.address }
                NearbyRow(
                    device = dev,
                    known = known,
                    summary = known?.station?.let { state.summaries[it] },
                    onClick = { openDevice(dev.address, dev.name) },
                    onForget = { forgetTarget = dev.address to dev.name },
                )
            }

            val knownNotNearby = state.known.filter { k -> state.nearby.none { it.address == k.address } }
            if (knownNotNearby.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)); Eyebrow(stringResource(R.string.scan_known)) }
                items(knownNotNearby, key = { "k:" + it.address }) { dev ->
                    KnownRow(
                        device = dev,
                        summary = dev.station?.let { state.summaries[it] },
                        now = now,
                        onLan = dev.station != null && dev.station in state.lanStations,
                        onClick = { openDevice(dev.address, dev.name) },
                        onForget = { forgetTarget = dev.address to dev.name },
                    )
                }
            }
            // Several loggers: one bounded background pass over all of them, LAN then bond per device.
            if (state.known.size >= 2) {
                item(key = "collect") {
                    Column(Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(
                            onClick = { withNotificationPermission { viewModel.collectAll() } },
                            enabled = !state.backgroundSyncActive,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.backgroundSyncActive) "Collecting in the background…" else "Collect from all ${state.known.size} devices")
                        }
                        Text(
                            "Collects from known devices over Wi-Fi when this phone holds their token, otherwise over their Bluetooth pairing. Up to two transfers run at once with a progress notification; you can leave the app. Devices with an interactive connection are skipped. An idle device page does not prevent background collection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                        )
                    }
                }
            }
        }
    }
    forgetTarget?.let { (address, name) ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${name ?: address}?") },
            text = {
                Text("This removes the saved device, its LAN access, and automatic collection settings from this phone. Files already collected stay here. The logger may still appear under Nearby while it is advertising.")
            },
            confirmButton = {
                TextButton(onClick = { forgetTarget = null; viewModel.forget(address) }) { Text("Forget device") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NearbyRow(device: ScannedDevice, known: KnownDevice?, summary: LoggerSummary?, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: "Unnamed logger", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (device.bonded) Tag("paired")
                    if (known?.station != null) Tag("known")
                }
                ArchiveLine(summary, rememberNowMillis())
            }
            Spacer(Modifier.width(12.dp))
            RssiMeter(device.rssi)
            if (known != null) ForgetMenu(onForget)
        }
    }
}

/** From the archive on this phone, never from a live link: the last UTC day with valid readings, as a mood. */
@Composable
private fun ArchiveLine(summary: LoggerSummary?, now: Long) {
    val mean = summary?.lastDayPm25Mean
    val dayStart = summary?.lastDayStartS
    if (mean != null && dayStart != null) {
        val mood = AirMood.of(mean.toDouble())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            MoodFace(mood, mood.color(), Modifier.size(16.dp))
            Text(
                "${mood.title} · avg ${"%.0f".format(java.util.Locale.ROOT, mean)} µg/m³ on ${DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(dayStart))}" +
                    (summary.lastDayCoverage?.let { " (${(it * 100).toInt()} % of the day)" } ?: "") +
                    " · ${summary.files} files" + (summary.lastSyncedEpochMs?.let { ", synced ${formatAgo(now - it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    } else if (summary != null && summary.lastDataUtcNs == null && summary.files > 0) {
        Text("${summary.files} files, none with a clock yet — give the logger the time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun KnownRow(device: KnownDevice, summary: LoggerSummary?, now: Long, onLan: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name ?: device.address, style = MaterialTheme.typography.titleMedium)
                    if (onLan) Tag(if (device.hasLanToken) "on LAN" else "on LAN · no token")
                }
                Text(
                    buildString {
                        append("seen ").append(formatAgo(now - device.lastSeenEpochMs))
                        if (summary != null) {
                            append(" · ").append(summary.files).append(" file").append(if (summary.files == 1) "" else "s")
                            summary.lastSyncedEpochMs?.let { append(", synced ").append(formatAgo(now - it)) }
                        } else {
                            device.station?.let { append(" · station ").append(it.take(8)) }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ArchiveLine(summary, now)
            }
            Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            ForgetMenu(onForget)
        }
    }
}

@Composable
private fun ForgetMenu(onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Device options") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Signal bars from RSSI; -50 is excellent, -90 is barely there. */
@Composable
private fun RssiMeter(rssi: Int) {
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..4) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (i <= bars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .width(5.dp)
                        .height((6 + i * 4).dp),
                ) {}
            }
        }
        Text("$rssi dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

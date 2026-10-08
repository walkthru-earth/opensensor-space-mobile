package earth.walkthru.aqsync.ui.scan

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.pluralStringResource
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
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.MascotGuide
import earth.walkthru.aqsync.ui.components.MoodFace
import earth.walkthru.aqsync.ui.components.SignalBars
import earth.walkthru.aqsync.ui.components.TickingText
import earth.walkthru.aqsync.ui.components.agoFormatter
import earth.walkthru.aqsync.ui.components.titleText
import earth.walkthru.aqsync.ui.permissions.AccessDialogs
import earth.walkthru.aqsync.ui.permissions.rememberBluetoothAccess
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

    // Bluetooth is asked for only from a tap (Scan, or opening a sensor). Once it is allowed and the
    // radio is on, the screen scans while visible. The radio is not free.
    val bluetooth = rememberBluetoothAccess()
    AccessDialogs(bluetooth)
    LifecycleStartEffect(bluetooth.ready) {
        if (bluetooth.ready) viewModel.startScan()
        onStopOrDispose { viewModel.stopScan() }
    }

    // The collect-all run shows a dataSync notification (API 33+ needs POST_NOTIFICATIONS); a denial only hides it.
    val notificationPermission = rememberNotificationPermission()
    fun withNotificationPermission(action: () -> Unit) = notificationPermission.ensureThen(action)

    // Detail owns persistent link/error state and remains useful with only the local archive.
    // Without Bluetooth the device still opens on its archive (History, Files) without a link.
    fun openDevice(address: String, name: String?) {
        bluetooth.run(onDeclined = { onConnected(address) }) {
            viewModel.connect(address, name)
            onConnected(address)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    InfoButton(
                        InfoContent(
                            stringResource(R.string.info_connect_title),
                            stringResource(R.string.info_connect_body1),
                            stringResource(R.string.info_connect_body2),
                            stringResource(R.string.info_privacy),
                        ),
                    )
                    if (state.showFakeEntry) {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.scan_more)) }
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
                onClick = { if (state.scanning) viewModel.stopScan() else bluetooth.run { viewModel.startScan() } },
                icon = { Icon(if (state.scanning) Icons.Rounded.Stop else Icons.Rounded.Radar, contentDescription = null) },
                text = { Text(stringResource(if (state.scanning) R.string.scan_button_stop else R.string.scan_button_scan)) },
            )
        },
    ) { padding ->
        val level = state.detailLevel
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.activeDevices.isNotEmpty()) {
                item(key = "active-devices") { Eyebrow(stringResource(R.string.scan_connections)) }
                items(state.activeDevices, key = { "active:" + it.address }) { active ->
                    Column(Modifier.fillMaxWidth().clickable { openDevice(active.address, active.name) }.padding(vertical = 8.dp)) {
                        Text(active.name ?: active.address, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        ConnectionBanner(active.connection, reconnecting = active.reconnect, technical = level.developer)
                        if (active.progress.running) {
                            Text(pluralStringResource(R.plurals.scan_collecting, active.progress.filesTotal, active.progress.filesDone, active.progress.filesTotal), style = MaterialTheme.typography.bodySmall)
                        } else if (active.backgroundOwner != null) {
                            Text(stringResource(R.string.scan_background), style = MaterialTheme.typography.bodySmall)
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

            if (state.nearby.isEmpty()) {
                // The peacock guides the empty moments: a first run with no devices, or no device nearby.
                item(key = "guide") {
                    if (state.known.isEmpty()) {
                        MascotGuide(
                            title = stringResource(R.string.scan_welcome_title),
                            body = stringResource(R.string.scan_welcome_body),
                        )
                    } else {
                        MascotGuide(
                            title = stringResource(R.string.scan_none_nearby_title),
                            body = stringResource(R.string.scan_none_nearby_body),
                        )
                    }
                }
            } else {
                item { Eyebrow(stringResource(R.string.scan_nearby)) }
            }
            items(state.nearby, key = { "n:" + it.address }) { dev ->
                val known = state.known.firstOrNull { it.address == dev.address }
                NearbyRow(
                    device = dev,
                    known = known,
                    summary = known?.station?.let { state.summaries[it] },
                    level = level,
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
                        level = level,
                        onLan = dev.station != null && dev.station in state.lanStations,
                        onClick = { openDevice(dev.address, dev.name) },
                        onForget = { forgetTarget = dev.address to dev.name },
                    )
                }
            }
            // Several loggers: one bounded background pass over all of them, LAN then bond per device.
            if (state.known.size >= 2) {
                item(key = "collect") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { withNotificationPermission { viewModel.collectAll() } },
                            enabled = !state.backgroundSyncActive,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.backgroundSyncActive) stringResource(R.string.scan_collect_running) else pluralStringResource(R.plurals.scan_collect_all, state.known.size, state.known.size))
                        }
                        val collectTitle = pluralStringResource(R.plurals.scan_collect_all, state.known.size, state.known.size)
                        InfoButton(InfoContent(collectTitle, stringResource(R.string.info_collect_body)))
                    }
                }
            }
        }
    }
    forgetTarget?.let { (address, name) ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.scan_forget_title, name ?: address)) },
            text = { Text(stringResource(R.string.scan_forget_body)) },
            confirmButton = {
                TextButton(onClick = { forgetTarget = null; viewModel.forget(address) }) { Text(stringResource(R.string.scan_forget_button)) }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NearbyRow(device: ScannedDevice, known: KnownDevice?, summary: LoggerSummary?, level: DetailLevel, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: stringResource(R.string.scan_unnamed), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (level.developer) {
                        Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (device.bonded) Tag(stringResource(R.string.scan_tag_paired))
                    if (known?.station != null) Tag(stringResource(R.string.scan_tag_known))
                }
                ArchiveLine(summary, level)
            }
            Spacer(Modifier.width(12.dp))
            SignalBars(device.rssi, showDbm = level.developer)
            if (known != null) ForgetMenu(onForget)
        }
    }
}

/**
 * From the archive on this phone, never from a live link: the last UTC day with valid readings, as
 * a mood. The "synced N ago" part is a leaf that owns the 1 Hz clock.
 */
@Composable
private fun ArchiveLine(summary: LoggerSummary?, level: DetailLevel) {
    val mean = summary?.lastDayPm25Mean
    val dayStart = summary?.lastDayStartS
    if (mean != null && dayStart != null) {
        val mood = AirMood.of(mean.toDouble())
        val metric = Metrics.primary
        val day = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(dayStart))
        val base = stringResource(
            R.string.scan_archive_day,
            mood.titleText(),
            metric.label,
            "%.0f".format(java.util.Locale.ROOT, mean),
            metric.unit,
            day,
        )
        val coverage = if (level.detailed) summary.lastDayCoverage?.let { ", " + stringResource(R.string.scan_archive_coverage, (it * 100).toInt()) } ?: "" else ""
        val files = if (level.detailed) ", " + pluralStringResource(R.plurals.scan_files, summary.files, summary.files) else ""
        val syncedTemplate = stringResource(R.string.scan_synced)
        val ago = agoFormatter()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            MoodFace(mood, mood.color(), Modifier.size(16.dp), description = null)
            val synced = summary.lastSyncedEpochMs
            if (synced != null) {
                TickingText(
                    { now -> "$base$coverage$files, ${syncedTemplate.format(ago(now - synced))}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            } else {
                Text("$base$coverage$files", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    } else if (summary != null && summary.lastDataUtcNs == null && summary.files > 0) {
        Text(pluralStringResource(R.plurals.scan_no_clock, summary.files, summary.files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun KnownRow(device: KnownDevice, summary: LoggerSummary?, level: DetailLevel, onLan: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name ?: stringResource(R.string.scan_unnamed), style = MaterialTheme.typography.titleMedium)
                    if (onLan) Tag(stringResource(if (device.hasLanToken) R.string.scan_tag_on_lan else R.string.scan_tag_on_lan_no_token))
                }
                val seenTemplate = stringResource(R.string.scan_seen)
                val ago = agoFormatter()
                val station = if (level.developer && summary == null) device.station?.let { ", " + stringResource(R.string.files_group_station, it.take(8)) } ?: "" else ""
                TickingText(
                    { now -> seenTemplate.format(ago(now - device.lastSeenEpochMs)) + station },
                    style = MaterialTheme.typography.bodySmall,
                )
                ArchiveLine(summary, level)
            }
            Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            ForgetMenu(onForget)
        }
    }
}

@Composable
private fun ForgetMenu(onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.scan_options)) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.scan_forget)) }, onClick = { menu = false; onForget() })
    }
}

/** A small pill tag, like the website's `.brand-eyebrow`. */
@Composable
private fun Tag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

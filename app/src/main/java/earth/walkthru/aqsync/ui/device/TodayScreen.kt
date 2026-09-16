package earth.walkthru.aqsync.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.StatusDot
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.formatBytes
import earth.walkthru.aqsync.ui.components.formatUptime
import earth.walkthru.aqsync.ui.components.rememberNowMillis
import earth.walkthru.aqsync.ui.settings.SettingsSheet
import earth.walkthru.aqsync.ui.theme.pm25Band
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reading older than this is shown as stale (the device samples every 10 s). */
private const val STALE_AFTER_MS = 30_000L

@Composable
fun TodayScreen(
    address: String,
    onOpenFiles: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenDebug: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNowMillis()
    // Hoisted so the scroll position belongs to the screen, not to whichever LazyColumn node
    // happens to be composed: a "Set time" tap must never reset it (see the keyed items below).
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
                is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            }
        }
    }
    // The repository dropped the session (user disconnected, or another device was picked).
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }

    var confirmTime by remember { mutableStateOf(false) }
    var confirmFlush by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.name ?: address, style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransportChip(state.transport)
                            state.info?.station?.let {
                                Text("station ${it.take(8)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    if (state.advancedMode) {
                        IconButton(onClick = onOpenDebug) { Icon(Icons.Rounded.BugReport, contentDescription = "Debug") }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                    IconButton(onClick = { viewModel.disconnect(); onDisconnected() }) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = "Disconnect")
                    }
                },
            )
        },
        bottomBar = {
            DeviceBottomBar(DeviceTab.TODAY) {
                when (it) {
                    DeviceTab.FILES -> onOpenFiles()
                    DeviceTab.DEVICE -> onOpenDevice()
                    DeviceTab.TODAY -> Unit
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // The banner lives outside the list: when it appears or disappears no list index shifts,
            // so the scroll position cannot jump.
            if (state.connection !is ConnectionState.Ready) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ConnectionBanner(state.connection)
                    if (state.connection is ConnectionState.Failed || state.connection is ConnectionState.Disconnected) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.reconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "readings") { ReadingsCard(state.live?.sample, state.live?.receivedAtEpochMs, now) }
                item(key = "particles") { ParticleRow(state.live?.sample) }
                item(key = "board") { BoardCard(state.live?.sample, state.status?.status) }
                item(key = "storage") { StorageCard(state.status?.status, state.status?.receivedAtEpochMs, now) }
                item(key = "clock") {
                    ClockCard(
                        status = state.status?.status,
                        live = state.live?.sample,
                        enabled = state.connection.isReady,
                        onSetTime = { confirmTime = true },
                        onFlush = { confirmFlush = true },
                    )
                }
                state.info?.let { info ->
                    item(key = "info") {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                            Eyebrow("device")
                            Spacer(Modifier.height(6.dp))
                            StatLine("firmware", info.fw ?: "—")
                            StatLine("schema", "${info.schema ?: "—"} · ${info.cols ?: "?"} cols")
                            StatLine("protocol", "v${info.proto ?: "?"}")
                            StatLine("device id", info.dev ?: "—")
                            StatLine("boot", info.boot?.take(12)?.plus("…") ?: "—")
                            StatLine("dictionary", info.dict?.take(12)?.plus("…") ?: "—")
                            StatLine("max_read", info.maxRead?.let { "$it B" } ?: "— (using default)")
                            StatLine("link", "${state.transport?.label ?: "—"}${state.connection.let { c -> if (c is ConnectionState.Ready) " · payload ${c.mtu - 3} B" else "" }}")
                        }
                    }
                }
            }
        }
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })

    if (confirmTime) {
        AlertDialog(
            onDismissRequest = { confirmTime = false },
            icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
            title = { Text(stringResource(R.string.set_time)) },
            text = {
                Text(
                    "Sends this phone's clock (${phoneClock()}) to the logger as its UTC anchor. " +
                        "Only rows sampled after this moment get a UTC timestamp and a dated folder; " +
                        "rows already recorded stay in the `unsynced` tree by contract and are never rewritten. " +
                        "Do this only if you trust the phone's time."
                )
            },
            confirmButton = { TextButton(onClick = { confirmTime = false; viewModel.setTimeFromPhone() }) { Text("Set time") } },
            dismissButton = { TextButton(onClick = { confirmTime = false }) { Text("Cancel") } },
        )
    }
    if (confirmFlush) {
        AlertDialog(
            onDismissRequest = { confirmFlush = false },
            icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
            title = { Text(stringResource(R.string.flush)) },
            text = {
                Text(
                    "The logger keeps the newest rows in RAM until the rotation interval ends. Finalizing writes them " +
                        "to the card now as a short Parquet file so you can sync everything up to this moment. " +
                        "It does not change the rotation interval."
                )
            },
            confirmButton = { TextButton(onClick = { confirmFlush = false; viewModel.flush() }) { Text("Finalize") } },
            dismissButton = { TextButton(onClick = { confirmFlush = false }) { Text("Cancel") } },
        )
    }
}

private fun phoneClock(): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.now())

// ------------------------------------------------------------------ readings

@Composable
private fun ReadingsCard(live: LiveSample?, receivedAt: Long?, now: Long) {
    val ageMs = receivedAt?.let { now - it }
    val stale = ageMs == null || ageMs > STALE_AFTER_MS
    val pmsStatus = live?.pmsStatus
    val showPm = live?.pmValid == true

    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("PM2.5")
                Spacer(Modifier.weight(1f))
                FreshnessLabel(ageMs, stale)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (showPm) fmt(live?.pm25) else "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else pm25Band(if (showPm) live.pm25 else null),
                )
                Spacer(Modifier.width(10.dp))
                Text("µg/m³", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
            }
            if (!showPm) {
                Text(
                    when (pmsStatus) {
                        null -> if (live == null) "Waiting for the first sample…" else "PM values absent (null, not zero)"
                        PmsStatus.VALID -> ""
                        else -> "PM sensor: ${pmsStatus.label}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigValue("PM10", if (showPm) fmt(live?.pm10) else "—", "µg/m³", Modifier.weight(1f))
                BigValue("PM1", if (showPm) fmt(live?.pm1) else "—", "µg/m³", Modifier.weight(1f))
            }
            if (showPm && (live?.c25 != null || live?.c1 != null || live?.c10 != null)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "CF=1 raw: ${fmt(live.c1)} / ${fmt(live.c25)} / ${fmt(live.c10)} µg/m³ (PM1 / PM2.5 / PM10)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            live?.seq?.let {
                Text("sample #$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FreshnessLabel(ageMs: Long?, stale: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(if (stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, size = 8)
        Text(
            when {
                ageMs == null -> "no sample yet"
                stale -> "stale · updated ${formatAgo(ageMs)}"
                else -> "updated ${formatAgo(ageMs)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigValue(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun ParticleRow(live: LiveSample?) {
    val counts = listOf(
        ">0.3" to live?.n03, ">0.5" to live?.n05, ">1" to live?.n1,
        ">2.5" to live?.n25, ">5" to live?.n5, ">10" to live?.n10,
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            // Not uppercased: "µ".uppercase() is the Greek capital mu, which rendered as "(MM)".
            Eyebrow("PARTICLES PER 0.1 L, BY SIZE (µm)", uppercase = false)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                counts.forEach { (label, n) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(live: LiveSample?, status: DeviceStatus?) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Eyebrow("board temp")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(live?.t?.let { String.format(Locale.US, "%.1f", it) } ?: "—", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(4.dp))
                    Text("°C", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(stringResource(R.string.board_temp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Column(Modifier.weight(1f)) {
                Eyebrow("battery")
                Row(verticalAlignment = Alignment.Bottom) {
                    Icon(
                        if (live?.chg == 1) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Text(live?.pct?.let { "$it" } ?: "—", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(4.dp))
                    Text("%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(
                    buildString {
                        live?.bat?.let { append(it).append(" mV") }
                        if (live?.chg == 1) append(" · charging")
                        live?.vbus?.takeIf { it > 0 }?.let { append(" · USB ").append(it).append(" mV") }
                        if (isEmpty()) append("—")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(status: DeviceStatus?, receivedAt: Long?, now: Long) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("storage")
                Spacer(Modifier.weight(1f))
                receivedAt?.let {
                    Text("status ${formatAgo(now - it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (status == null) {
                Text("No status yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val warn = MaterialTheme.colorScheme.error
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("files", status.fin)
                Counter("buffered rows", status.buf?.toLong())
                Counter("dropped", status.drop, if ((status.drop ?: 0) > 0) warn else null)
                Counter("errors", status.err, if ((status.err ?: 0) > 0) warn else null)
                Counter("missed", status.miss, if ((status.miss ?: 0) > 0) warn else null)
            }
            Spacer(Modifier.height(10.dp))
            StatLine(
                "microSD",
                when {
                    status.sd == null -> "—"
                    !status.sdMounted -> "not mounted"
                    else -> "${status.sdUsedKib?.let { formatBytes(it * 1024) } ?: "?"} of ${status.sdKib?.let { formatBytes(it * 1024) } ?: "?"} used"
                },
                valueColor = if (status.sd == 0) warn else MaterialTheme.colorScheme.onSurface,
            )
            StatLine("codec", status.codec ?: "—")
            StatLine("rotation", status.intervalSeconds?.let { "${it / 60} min" } ?: "—")
            StatLine("retained .partial", status.part?.toString() ?: "—")
            StatLine("uptime", status.upSeconds?.let(::formatUptime) ?: "—")
            StatLine("free heap", status.heap?.let { formatBytes(it) } ?: "—")
            if (status.storageFailed) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "Storage worker has stopped writing after an error. New rows are not being saved.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Long?, color: Color? = null) {
    Column {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClockCard(status: DeviceStatus?, live: LiveSample?, enabled: Boolean, onSetTime: () -> Unit, onFlush: () -> Unit) {
    val synced = status?.utcSynced
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Eyebrow("clock")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (synced) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.tertiary
                        null -> MaterialTheme.colorScheme.outline
                    }
                )
                Text(
                    when (synced) {
                        true -> "UTC anchored (generation ${status.gen ?: "?"})"
                        false -> "No UTC — rows go to the `unsynced` tree"
                        null -> "Unknown"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            live?.utc?.let { ns ->
                val inst = Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L)
                Text(
                    "last sample at " + DateTimeFormatter.ISO_INSTANT.format(inst),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onSetTime, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.set_time))
                }
                OutlinedButton(onClick = onFlush, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.flush))
                }
            }
            Text(
                "Set time: only rows after this get UTC. Finalize: writes the RAM batch to a short file so it can be synced now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun fmt(v: Double?): String = when {
    v == null -> "—"
    v == Math.floor(v) && v < 1000 -> String.format(Locale.US, "%.0f", v)
    else -> String.format(Locale.US, "%.1f", v)
}

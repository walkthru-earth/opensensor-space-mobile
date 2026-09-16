package earth.walkthru.aqsync.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.LogTailState
import earth.walkthru.aqsync.data.ble.FrameDirection
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.WarningNote
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.rememberNowMillis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Advanced mode only. Everything here is raw and read-mostly: the status JSON as received, the
 * last frames on the link, the device's serial log ring, and a raw command box. Reachable only
 * while the Advanced switch is on; the route pops itself when the switch is turned off.
 */
@Composable
fun DebugScreen(
    address: String,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val now = rememberNowMillis()

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
                is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            }
        }
    }
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }
    // The destination exists only in Advanced mode. `advancedMode` starts false in the initial
    // state, so wait for the first real value before deciding.
    var seenAdvanced by remember { mutableStateOf(false) }
    LaunchedEffect(state.advancedMode) {
        if (state.advancedMode) seenAdvanced = true else if (seenAdvanced) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                title = {
                    Column {
                        Text("Debug", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransportChip(state.transport)
                            Text(state.endpoint ?: address, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (!state.connection.isReady) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ConnectionBanner(state.connection) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "link") {
                    SectionCard("active transport") {
                        StatLine("transport", state.transport?.label ?: "—")
                        StatLine("endpoint", state.endpoint ?: "—")
                        StatLine("payload_max", state.payloadMax?.let { "$it B" } ?: "—")
                        StatLine("state", state.connection.let { c -> c::class.simpleName ?: "?" })
                    }
                }
                item(key = "status") {
                    SectionCard(
                        "status json (live)",
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.statusAtEpochMs?.let { Text(formatAgo(now - it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(onClick = { viewModel.requestStatus() }, enabled = state.connection.isReady) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Request status (0x07)")
                                }
                            }
                        },
                    ) {
                        MonoBox(state.statusJsonPretty ?: "no status received yet", maxHeight = 320)
                    }
                }
                item(key = "config") {
                    SectionCard("config json") {
                        MonoBox(state.configJsonPretty ?: "no CONFIG received (protocol v1 device, or not read yet)", maxHeight = 320)
                    }
                }
                item(key = "frames") {
                    SectionCard(
                        "last ${state.frames.size} raw frames (newest first)",
                        trailing = { TextButton(onClick = { viewModel.clearFrames() }) { Text("Clear") } },
                    ) {
                        if (state.frames.isEmpty()) {
                            Text("Nothing captured yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                state.frames.forEach { f ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (f.direction == FrameDirection.TX) "→" else "←",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (f.direction == FrameDirection.TX) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(18.dp),
                                        )
                                        Text(
                                            "%s %-13s %5d B  %s%s".format(
                                                timeOf(f.atEpochMs), f.typeName, f.length, f.previewHex,
                                                if (f.redacted) " <redacted>" else if (f.length > 16) "…" else "",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        )
                                    }
                                }
                            }
                        }
                        InfoNote("TX = requests written to `control`; RX = frames from `response`. Pushed status/live JSON is not listed here. TOKEN bodies and SET_CONFIG requests are redacted.")
                    }
                }
                item(key = "log") {
                    val log = state.log
                    SectionCard(
                        "device log (LOG_TAIL 8192)",
                        trailing = {
                            FilledTonalButton(onClick = { viewModel.refreshLog() }, enabled = state.connection.isReady && log !is LogTailState.Loading) {
                                if (log is LogTailState.Loading) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(if (log is LogTailState.Idle) "Device log" else "Refresh")
                            }
                        },
                    ) {
                        when (log) {
                            LogTailState.Idle -> Text("Fetches the newest 8 KiB of the device's serial log ring — the same PARQUET / BLE / WIFI lines a bench capture shows. Text for humans, not an API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LogTailState.Loading -> Text("Fetching…", style = MaterialTheme.typography.bodySmall)
                            is LogTailState.Failed -> WarningNote(log.message)
                            is LogTailState.Loaded -> {
                                Text(
                                    "${log.tail.returned} B returned of ${log.tail.totalBytesSinceBoot} B logged since boot · ${formatAgo(now - log.atEpochMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!log.tail.complete) {
                                    Spacer(Modifier.height(4.dp))
                                    WarningNote("Received ${log.tail.receivedBytes} B but LOG_END says ${log.tail.returned}: a slice may be missing.")
                                }
                                Spacer(Modifier.height(6.dp))
                                MonoBox(log.tail.text.ifEmpty { "(empty)" }, maxHeight = 420)
                            }
                        }
                    }
                }
                item(key = "raw") {
                    var hex by rememberSaveable { mutableStateOf("08") }
                    SectionCard("raw command") {
                        Text(
                            "Hex bytes of one `control` request (first byte = opcode). The reply frames are collected until the device is quiet for 1.5 s. " +
                                "Examples: 01 LIST · 07 STATUS · 08 GET_CONFIG · 0c 0020 LOG_TAIL 8192 · 0d GET_TOKEN (BLE only).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = hex,
                                onValueChange = { hex = it },
                                label = { Text("hex") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
                                modifier = Modifier.weight(1f),
                                isError = state.rawError != null,
                            )
                            FilledTonalButton(onClick = { viewModel.sendRaw(hex) }, enabled = state.connection.isReady && !state.rawBusy) {
                                if (state.rawBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                            }
                        }
                        state.rawError?.let {
                            Spacer(Modifier.height(6.dp))
                            WarningNote(it)
                        }
                        if (state.rawResults.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("${state.rawResults.size} frame(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MonoBox(
                                state.rawResults.joinToString("\n") { r -> "${r.decoded}\n    ${r.hex.take(96)}${if (r.hex.length > 96) "…" else ""}" },
                                maxHeight = 320,
                            )
                        }
                        InfoNote("Only reads and the documented state changes are wise here; the device never deletes or rewrites anything on request, so the worst case is a rejected frame.")
                    }
                }
            }
        }
    }
}

@Composable
private fun MonoBox(text: String, maxHeight: Int) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = maxHeight.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private fun timeOf(epochMs: Long): String = timeFmt.format(Instant.ofEpochMilli(epochMs))

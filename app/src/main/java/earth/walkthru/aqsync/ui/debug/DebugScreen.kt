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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.LogTailState
import earth.walkthru.aqsync.data.ble.FrameDirection
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.agoFormatter
import earth.walkthru.aqsync.ui.components.labelText
import earth.walkthru.aqsync.ui.components.showDeviceEvent
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoButton
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.SectionCard
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.TickingText
import earth.walkthru.opensensor.designsystem.WarningNote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Developer level only. Everything here is raw and read-mostly: the status JSON as received, the
 * last frames on the link, the device's serial log ring, and a raw command box. The route pops
 * itself when the level drops below Developer.
 */
@Composable
fun DebugScreen(
    address: String,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel<DebugViewModel, DebugViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val ago = agoFormatter()

    val clockHint = stringResource(R.string.event_clock_on_today)
    val res = LocalResources.current
    CollectEvents(viewModel.events) { ev ->
        snackbar.showDeviceEvent(res, ev) { snackbar.showSnackbar(clockHint) }
    }
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }
    // The destination exists only in Advanced mode. `advancedMode` starts false in the initial
    // state, so wait for the first real value before deciding.
    var seenAdvanced by remember { mutableStateOf(false) }
    LaunchedEffect(state.advancedMode) {
        if (state.advancedMode) {
            seenAdvanced = true
        } else if (seenAdvanced) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.nav_debug), style = MaterialTheme.typography.titleLarge)
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
                    SectionCard(stringResource(R.string.debug_transport_title)) {
                        StatLine(stringResource(R.string.debug_transport), state.transport?.labelText() ?: NO_VALUE)
                        StatLine(stringResource(R.string.debug_endpoint), state.endpoint ?: NO_VALUE)
                        StatLine("payload_max", state.payloadMax?.let { "$it B" } ?: NO_VALUE)
                        StatLine(stringResource(R.string.debug_state), state.connection.let { c -> c::class.simpleName ?: NO_VALUE })
                    }
                }
                item(key = "sync-diagnostics") {
                    SectionCard(stringResource(R.string.debug_sync_title)) {
                        val a = state.archiveTime
                        StatLine(stringResource(R.string.debug_archive), files(a.files) + " · " + rows(a.rows))
                        StatLine(stringResource(R.string.debug_real_utc), rows(a.realTimeRows))
                        StatLine(stringResource(R.string.debug_estimate), rows(a.estimatedRows) + " · " + files(a.estimatedFiles))
                        StatLine(
                            stringResource(R.string.debug_unplaced),
                            rows(a.unplacedRows) + " · " + files(a.unplacedFiles) + " · " + pluralStringResource(R.plurals.debug_boots, a.unplacedBoots, a.unplacedBoots),
                        )
                        Spacer(Modifier.height(8.dp))
                        MonoBox(
                            state.syncDiagnostics.asReversed().joinToString("\n") { d ->
                                "${timeOf(d.atEpochMs)}  ${d.step.padEnd(10)} ${d.detail}"
                            }.ifEmpty { stringResource(R.string.debug_sync_none) },
                            maxHeight = 240,
                        )
                    }
                }
                item(key = "status") {
                    SectionCard(
                        stringResource(R.string.debug_status_title),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.statusAtEpochMs?.let { at -> TickingText({ now -> ago(now - at) }) }
                                IconButton(onClick = { viewModel.requestStatus() }, enabled = state.connection.isReady) {
                                    Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.debug_request_status))
                                }
                            }
                        },
                    ) {
                        MonoBox(state.statusJsonPretty ?: stringResource(R.string.debug_status_none), maxHeight = 320)
                    }
                }
                item(key = "config") {
                    SectionCard(stringResource(R.string.debug_config_title)) {
                        MonoBox(state.configJsonPretty ?: stringResource(R.string.debug_config_none), maxHeight = 320)
                    }
                }
                item(key = "frames") {
                    SectionCard(
                        pluralStringResource(R.plurals.debug_frames_title, state.frames.size, state.frames.size),
                        trailing = { TextButton(onClick = { viewModel.clearFrames() }) { Text(stringResource(R.string.debug_clear)) } },
                    ) {
                        if (state.frames.isEmpty()) {
                            Text(stringResource(R.string.debug_frames_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                timeOf(f.atEpochMs),
                                                f.typeName,
                                                f.length,
                                                f.previewHex,
                                                if (f.redacted) {
                                                    " <redacted>"
                                                } else if (f.length > 16) {
                                                    "…"
                                                } else {
                                                    ""
                                                },
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
                        InfoNote(stringResource(R.string.debug_frames_note))
                    }
                }
                item(key = "log") {
                    val log = state.log
                    SectionCard(
                        stringResource(R.string.debug_log_title),
                        trailing = {
                            FilledTonalButton(onClick = { viewModel.refreshLog() }, enabled = state.connection.isReady && log !is LogTailState.Loading, shape = FloatingControl.SmallShape) {
                                if (log is LogTailState.Loading) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (log is LogTailState.Idle) R.string.debug_log_fetch else R.string.debug_log_refresh))
                            }
                        },
                    ) {
                        when (log) {
                            LogTailState.Idle -> Text(stringResource(R.string.debug_log_idle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LogTailState.Loading -> Text(stringResource(R.string.debug_log_loading), style = MaterialTheme.typography.bodySmall)
                            is LogTailState.Failed -> WarningNote(stringResource(R.string.debug_log_failed, log.message))
                            is LogTailState.Loaded -> {
                                val summary = stringResource(R.string.debug_log_summary, log.tail.returned, log.tail.totalBytesSinceBoot)
                                TickingText(
                                    { now -> "$summary · ${ago(now - log.atEpochMs)}" },
                                    maxLines = 2,
                                )
                                if (!log.tail.complete) {
                                    Spacer(Modifier.height(4.dp))
                                    WarningNote(stringResource(R.string.debug_log_incomplete, log.tail.receivedBytes, log.tail.returned))
                                }
                                Spacer(Modifier.height(6.dp))
                                MonoBox(log.tail.text.ifEmpty { stringResource(R.string.debug_log_empty) }, maxHeight = 420)
                            }
                        }
                    }
                }
                item(key = "raw") {
                    var hex by rememberSaveable { mutableStateOf("08") }
                    val rawTitle = stringResource(R.string.debug_raw_title)
                    SectionCard(
                        rawTitle,
                        trailing = {
                            InfoButton(
                                InfoContent(
                                    rawTitle,
                                    stringResource(R.string.debug_raw_info1),
                                    stringResource(R.string.debug_raw_info2),
                                    stringResource(R.string.debug_raw_info3),
                                ),
                            )
                        },
                    ) {
                        Text(
                            stringResource(R.string.debug_raw_examples),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = hex,
                                onValueChange = { hex = it },
                                label = { Text(stringResource(R.string.debug_raw_hex)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
                                modifier = Modifier.weight(1f),
                                isError = state.rawError != null,
                            )
                            FilledTonalButton(onClick = { viewModel.sendRaw(hex) }, enabled = state.connection.isReady && !state.rawBusy, shape = FloatingControl.SmallShape) {
                                if (state.rawBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(AppIcons.Send, contentDescription = stringResource(R.string.debug_raw_send))
                            }
                        }
                        state.rawError?.let {
                            Spacer(Modifier.height(6.dp))
                            WarningNote(rawErrorText(it))
                        }
                        if (state.rawResults.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(pluralStringResource(R.plurals.debug_raw_frames, state.rawResults.size, state.rawResults.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MonoBox(
                                state.rawResults.joinToString("\n") { r -> "${r.decoded}\n    ${r.hex.take(96)}${if (r.hex.length > 96) "…" else ""}" },
                                maxHeight = 320,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rawErrorText(e: RawError): String = when (e) {
    RawError.BackgroundOwns -> stringResource(R.string.msg_background_owns_command)
    RawError.InvalidHex -> stringResource(R.string.debug_raw_invalid_hex)
    RawError.BadLength -> stringResource(R.string.debug_raw_bad_length)
    is RawError.NeedsConfirmation -> stringResource(R.string.debug_raw_needs_confirmation, e.opcode)
    RawError.NoResponse -> stringResource(R.string.debug_raw_no_response)
    is RawError.Failed -> e.detail
}

@Composable
private fun files(n: Int): String = pluralStringResource(R.plurals.scan_files, n, n)

@Composable
private fun rows(n: Long): String = pluralStringResource(R.plurals.fm_rows_count, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)

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

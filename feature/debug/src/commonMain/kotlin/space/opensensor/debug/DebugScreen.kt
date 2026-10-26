package space.opensensor.debug

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.FrameDirection
import space.opensensor.data.ble.RawFrameRecord
import space.opensensor.data.ble.TransportKind
import space.opensensor.debug.resources.Res
import space.opensensor.debug.resources.action_back
import space.opensensor.debug.resources.ago_days
import space.opensensor.debug.resources.ago_hours
import space.opensensor.debug.resources.ago_just_now
import space.opensensor.debug.resources.ago_minutes
import space.opensensor.debug.resources.ago_seconds
import space.opensensor.debug.resources.conn_connecting
import space.opensensor.debug.resources.conn_disconnected
import space.opensensor.debug.resources.debug_archive
import space.opensensor.debug.resources.debug_boots
import space.opensensor.debug.resources.debug_clear
import space.opensensor.debug.resources.debug_config_none
import space.opensensor.debug.resources.debug_config_read
import space.opensensor.debug.resources.debug_config_title
import space.opensensor.debug.resources.debug_endpoint
import space.opensensor.debug.resources.debug_estimate
import space.opensensor.debug.resources.debug_frame_received
import space.opensensor.debug.resources.debug_frame_sent
import space.opensensor.debug.resources.debug_frames_none
import space.opensensor.debug.resources.debug_frames_note
import space.opensensor.debug.resources.debug_frames_title
import space.opensensor.debug.resources.debug_link_busy
import space.opensensor.debug.resources.debug_log_empty
import space.opensensor.debug.resources.debug_log_failed
import space.opensensor.debug.resources.debug_log_fetch
import space.opensensor.debug.resources.debug_log_idle
import space.opensensor.debug.resources.debug_log_incomplete
import space.opensensor.debug.resources.debug_log_loading
import space.opensensor.debug.resources.debug_log_refresh
import space.opensensor.debug.resources.debug_log_summary
import space.opensensor.debug.resources.debug_log_title
import space.opensensor.debug.resources.debug_raw_bad_length
import space.opensensor.debug.resources.debug_raw_examples
import space.opensensor.debug.resources.debug_raw_frames
import space.opensensor.debug.resources.debug_raw_hex
import space.opensensor.debug.resources.debug_raw_info1
import space.opensensor.debug.resources.debug_raw_info2
import space.opensensor.debug.resources.debug_raw_info3
import space.opensensor.debug.resources.debug_raw_invalid_hex
import space.opensensor.debug.resources.debug_raw_needs_confirmation
import space.opensensor.debug.resources.debug_raw_no_response
import space.opensensor.debug.resources.debug_raw_send
import space.opensensor.debug.resources.debug_raw_title
import space.opensensor.debug.resources.debug_real_utc
import space.opensensor.debug.resources.debug_request_status
import space.opensensor.debug.resources.debug_state
import space.opensensor.debug.resources.debug_status_none
import space.opensensor.debug.resources.debug_status_title
import space.opensensor.debug.resources.debug_sync_none
import space.opensensor.debug.resources.debug_sync_title
import space.opensensor.debug.resources.debug_transport
import space.opensensor.debug.resources.debug_transport_title
import space.opensensor.debug.resources.debug_unplaced
import space.opensensor.debug.resources.fm_rows_count
import space.opensensor.debug.resources.msg_background_owns_command
import space.opensensor.debug.resources.nav_debug
import space.opensensor.debug.resources.scan_files
import space.opensensor.debug.resources.transport_ble
import space.opensensor.debug.resources.transport_fake
import space.opensensor.debug.resources.transport_lan
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.SectionCard
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.TickingText
import space.opensensor.designsystem.WarningNote
import kotlin.time.Instant

private const val NO_VALUE = "n/a"

/**
 * Developer level only, on Android and iOS. Everything here is raw and read-mostly: the status JSON
 * as received, the last frames on the link, the device's serial log ring, and a raw command box
 * that refuses `REBOOT` and `SET_CONFIG`. Secrets never reach this screen, the frames and JSON
 * arrive redacted ([space.opensensor.data.ble.FrameRedaction]).
 *
 * [developer] is the current detail level test, null until the stored level loads. The route pops
 * itself when it turns false after having been true. [connectionBanner] lets a platform show its own
 * banner while the link is not ready, otherwise a plain note says the sensor is not connected.
 */
@Composable
fun DebugScreen(
    controller: DebugController,
    address: String,
    developer: Boolean?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    connectionBanner: (@Composable (ConnectionState) -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()
    val ago = agoFormatter()

    // Null while the stored level loads. Any confirmed level below Developer leaves at once.
    LaunchedEffect(developer) {
        if (leavesDebug(developer)) onBack()
    }

    val ready = state.link.connection.isReady
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back)) } },
                title = {
                    Column {
                        Text(stringResource(Res.string.nav_debug), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.link.transport?.let {
                                Text(transportLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(state.link.endpoint ?: address, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (!ready) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (connectionBanner != null) {
                        connectionBanner(state.link.connection)
                    } else {
                        WarningNote(stringResource(if (state.link.connection.isBusy) Res.string.conn_connecting else Res.string.conn_disconnected))
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "link") { LinkCard(state.link) }
                state.syncTrace?.let { trace -> item(key = "sync-diagnostics") { SyncCard(trace) } }
                item(key = "status") {
                    SectionCard(
                        stringResource(Res.string.debug_status_title),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.statusAtEpochMs?.let { at -> TickingText({ now -> ago(now - at) }) }
                                IconButton(onClick = controller::requestStatus, enabled = ready) {
                                    Icon(AppIcons.Refresh, contentDescription = stringResource(Res.string.debug_request_status))
                                }
                            }
                        },
                    ) {
                        MonoBox(state.statusJsonPretty ?: stringResource(Res.string.debug_status_none), maxHeight = 320)
                    }
                }
                item(key = "config") {
                    SectionCard(
                        stringResource(Res.string.debug_config_title),
                        trailing = if (state.configOnRequest) {
                            {
                                IconButton(onClick = controller::refreshConfig, enabled = ready) {
                                    Icon(AppIcons.Refresh, contentDescription = stringResource(Res.string.debug_config_read))
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        MonoBox(state.configJsonPretty ?: stringResource(Res.string.debug_config_none), maxHeight = 320)
                    }
                }
                item(key = "frames") { FramesCard(state.frames, controller::clearFrames) }
                item(key = "log") { LogCard(state.log, ready, ago, controller::refreshLog) }
                item(key = "raw") { RawCard(state, ready, controller::sendRaw) }
            }
        }
    }
}

@Composable
private fun LinkCard(link: DebugLink) {
    SectionCard(stringResource(Res.string.debug_transport_title)) {
        StatLine(stringResource(Res.string.debug_transport), link.transport?.let { transportLabel(it) } ?: NO_VALUE)
        StatLine(stringResource(Res.string.debug_endpoint), link.endpoint ?: NO_VALUE)
        StatLine("payload_max", link.payloadMax?.let { "$it B" } ?: NO_VALUE)
        StatLine(stringResource(Res.string.debug_state), connectionName(link.connection))
    }
}

@Composable
private fun SyncCard(trace: DebugSyncTrace) {
    SectionCard(stringResource(Res.string.debug_sync_title)) {
        val a = trace.archive
        StatLine(stringResource(Res.string.debug_archive), files(a.files) + " · " + rows(a.rows))
        StatLine(stringResource(Res.string.debug_real_utc), rows(a.realTimeRows))
        StatLine(stringResource(Res.string.debug_estimate), rows(a.estimatedRows) + " · " + files(a.estimatedFiles))
        StatLine(
            stringResource(Res.string.debug_unplaced),
            rows(a.unplacedRows) + " · " + files(a.unplacedFiles) + " · " + pluralStringResource(Res.plurals.debug_boots, a.unplacedBoots, a.unplacedBoots),
        )
        Spacer(Modifier.height(8.dp))
        MonoBox(
            trace.steps.asReversed().joinToString("\n") { d -> "${timeOf(d.atEpochMs)}  ${d.step.padEnd(10)} ${d.detail}" }
                .ifEmpty { stringResource(Res.string.debug_sync_none) },
            maxHeight = 240,
        )
    }
}

@Composable
private fun FramesCard(frames: List<RawFrameRecord>, onClear: () -> Unit) {
    SectionCard(
        pluralStringResource(Res.plurals.debug_frames_title, frames.size, frames.size),
        trailing = { TextButton(onClick = onClear) { Text(stringResource(Res.string.debug_clear)) } },
    ) {
        if (frames.isEmpty()) {
            Text(stringResource(Res.string.debug_frames_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                frames.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tx = f.direction == FrameDirection.TX
                        // The arrow is read as a word, not as "right arrow".
                        val direction = stringResource(if (tx) Res.string.debug_frame_sent else Res.string.debug_frame_received)
                        Text(
                            if (tx) "→" else "←",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (tx) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(18.dp).semantics { contentDescription = direction },
                        )
                        Text(
                            frameLine(f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
        InfoNote(stringResource(Res.string.debug_frames_note))
    }
}

@Composable
private fun LogCard(log: DebugLog, ready: Boolean, ago: (Long) -> String, onRefresh: () -> Unit) {
    SectionCard(
        stringResource(Res.string.debug_log_title),
        trailing = {
            FilledTonalButton(onClick = onRefresh, enabled = ready && log !is DebugLog.Loading, shape = FloatingControl.SmallShape) {
                if (log is DebugLog.Loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (log is DebugLog.Idle) Res.string.debug_log_fetch else Res.string.debug_log_refresh))
            }
        },
    ) {
        when (log) {
            DebugLog.Idle -> Text(stringResource(Res.string.debug_log_idle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DebugLog.Loading -> Text(stringResource(Res.string.debug_log_loading), style = MaterialTheme.typography.bodySmall)
            is DebugLog.Failed -> WarningNote(stringResource(Res.string.debug_log_failed, log.message))
            is DebugLog.Loaded -> {
                val summary = stringResource(Res.string.debug_log_summary, log.tail.returned, log.tail.totalBytesSinceBoot)
                TickingText({ now -> "$summary · ${ago(now - log.atEpochMs)}" }, maxLines = 2)
                if (!log.tail.complete) {
                    Spacer(Modifier.height(4.dp))
                    WarningNote(stringResource(Res.string.debug_log_incomplete, log.tail.receivedBytes, log.tail.returned))
                }
                Spacer(Modifier.height(6.dp))
                MonoBox(log.tail.text.ifEmpty { stringResource(Res.string.debug_log_empty) }, maxHeight = 420)
            }
        }
    }
}

@Composable
private fun RawCard(state: DebugUiState, ready: Boolean, onSend: (String) -> Unit) {
    var hex by rememberSaveable { mutableStateOf("08") }
    val rawTitle = stringResource(Res.string.debug_raw_title)
    SectionCard(
        rawTitle,
        trailing = {
            InfoButton(
                InfoContent(
                    rawTitle,
                    stringResource(Res.string.debug_raw_info1),
                    stringResource(Res.string.debug_raw_info2),
                    stringResource(Res.string.debug_raw_info3),
                ),
            )
        },
    ) {
        Text(
            stringResource(Res.string.debug_raw_examples),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it },
                label = { Text(stringResource(Res.string.debug_raw_hex)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
                isError = state.rawError != null,
            )
            FilledTonalButton(onClick = { onSend(hex) }, enabled = ready && !state.rawBusy, shape = FloatingControl.SmallShape) {
                if (state.rawBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Send, contentDescription = stringResource(Res.string.debug_raw_send))
                }
            }
        }
        state.rawError?.let {
            Spacer(Modifier.height(6.dp))
            WarningNote(rawErrorText(it))
        }
        if (state.rawResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(Res.plurals.debug_raw_frames, state.rawResults.size, state.rawResults.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoBox(
                state.rawResults.joinToString("\n") { r -> "${r.decoded}\n    ${r.hex.take(96)}${if (r.hex.length > 96) "…" else ""}" },
                maxHeight = 320,
            )
        }
    }
}

@Composable
private fun rawErrorText(e: RawError): String = when (e) {
    RawError.BackgroundOwns -> stringResource(Res.string.msg_background_owns_command)
    RawError.LinkBusy -> stringResource(Res.string.debug_link_busy)
    RawError.InvalidHex -> stringResource(Res.string.debug_raw_invalid_hex)
    RawError.BadLength -> stringResource(Res.string.debug_raw_bad_length)
    is RawError.NeedsConfirmation -> stringResource(Res.string.debug_raw_needs_confirmation, e.opcode)
    RawError.NoResponse -> stringResource(Res.string.debug_raw_no_response)
    is RawError.Failed -> e.detail
}

@Composable
private fun transportLabel(kind: TransportKind): String = stringResource(
    when (kind) {
        TransportKind.BLE -> Res.string.transport_ble
        TransportKind.LAN -> Res.string.transport_lan
        TransportKind.FAKE -> Res.string.transport_fake
    },
)

/** The state's type name, as Android showed it, without reflection. */
private fun connectionName(c: ConnectionState): String = when (c) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.DiscoveringServices -> "DiscoveringServices"
    ConnectionState.Bonding -> "Bonding"
    ConnectionState.NegotiatingMtu -> "NegotiatingMtu"
    ConnectionState.EnablingNotifications -> "EnablingNotifications"
    ConnectionState.Authenticating -> "Authenticating"
    is ConnectionState.Ready -> "Ready"
    is ConnectionState.Failed -> "Failed"
}

@Composable
private fun files(n: Int): String = pluralStringResource(Res.plurals.scan_files, n, n)

@Composable
private fun rows(n: Long): String = pluralStringResource(Res.plurals.fm_rows_count, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)

/** Marker that stands in for the count, so a non-composable ticking lambda can fill it in. */
private const val COUNT = ""

/** "just now", "12 s ago" and so on, from the module's catalogue. */
@Composable
private fun agoFormatter(): (Long) -> String {
    val now = stringResource(Res.string.ago_just_now)
    val s = stringResource(Res.string.ago_seconds, COUNT)
    val m = stringResource(Res.string.ago_minutes, COUNT)
    val h = stringResource(Res.string.ago_hours, COUNT)
    val d = stringResource(Res.string.ago_days, COUNT)
    return remember(now, s, m, h, d) { { ageMs -> formatAgo(ageMs, now, s, m, h, d) } }
}

internal fun formatAgo(ageMs: Long, now: String, s: String, m: String, h: String, d: String): String {
    val sec = ageMs / 1000
    return when {
        ageMs < 0 -> now
        sec < 60 -> s.replace(COUNT, sec.toString())
        sec < 3600 -> m.replace(COUNT, (sec / 60).toString())
        sec < 86_400 -> h.replace(COUNT, (sec / 3600).toString())
        else -> d.replace(COUNT, (sec / 86_400).toString())
    }
}

/** True once the stored level is known and is below Developer. Null (still loading) stays. */
internal fun leavesDebug(developer: Boolean?): Boolean = developer == false

/** `HH:mm:ss.SSS TYPE          LEN B  PREVIEW`, the Android layout without `String.format`. */
internal fun frameLine(f: RawFrameRecord): String {
    val tail = when {
        f.redacted -> " <redacted>"
        f.length > RawFrameRecord.PREVIEW_BYTES -> "…"
        else -> ""
    }
    return "${timeOf(f.atEpochMs)} ${f.typeName.padEnd(13)} ${(if (f.redacted) "-" else f.length.toString()).padStart(5)} B  ${f.previewHex}$tail"
}

internal fun timeOf(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(t.hour)}:${two(t.minute)}:${two(t.second)}.${(t.nanosecond / 1_000_000).toString().padStart(3, '0')}"
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

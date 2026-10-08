package earth.walkthru.aqsync.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncLock
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ManifestState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.ui.components.CollectEvents
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.MascotGuide
import earth.walkthru.aqsync.ui.components.TickingText
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.formatBytes
import earth.walkthru.aqsync.ui.components.formatRate
import earth.walkthru.aqsync.ui.permissions.rememberNotificationPermission

@Composable
fun FilesScreen(
    address: String,
    onOpenToday: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenFileMetrics: (station: String, name: String) -> Unit,
    onDisconnected: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel<FilesViewModel, FilesViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val level = state.detailLevel
    val clockHint = stringResource(R.string.event_clock_on_today)

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onDocumentCreated(uri)
    }
    // The sync runs under a dataSync foreground service whose notification needs POST_NOTIFICATIONS
    // on API 33+. Ask once, right before the first sync; whatever the answer, the sync starts —
    // a denial only hides the notification.
    val notificationPermission = rememberNotificationPermission()
    fun withNotificationPermission(action: () -> Unit) = notificationPermission.ensureThen(action)

    CollectEvents(viewModel.events) { ev ->
        when (ev) {
            is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
            is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            is DeviceEvent.ClockConfirmationRequired -> snackbar.showSnackbar(clockHint)
        }
    }
    CollectEvents(viewModel.effects) { fx ->
        when (fx) {
            is FilesEffect.Launch -> context.startActivity(fx.intent)
            is FilesEffect.CreateDocument -> createDoc.launch(fx.suggestedName)
        }
    }

    var menu by remember { mutableStateOf(false) }
    val plan = state.plan

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransportChip(state.transport)
                            Text(
                                "${state.archived.size} in archive · ${formatBytes(state.archivedBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = state.connection.isReady && !state.manifest.isLoading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh list")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Export") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val hasFiles = state.archived.isNotEmpty() && !state.exporting
                        // Background sync: a one-time WorkManager job (leave the app, watch the
                        // notification) and the per-device Auto-sync switch, both also on the Device screen.
                        DropdownMenuItem(
                            text = { Text(if (state.backgroundSyncActive) "Background sync queued…" else "Sync now in background") },
                            leadingIcon = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                            enabled = state.connection.isReady && !state.sync.running && !state.backgroundSyncActive,
                            onClick = { menu = false; withNotificationPermission { viewModel.syncNowInBackground() } },
                        )
                        state.autoSync?.let { on ->
                            DropdownMenuItem(
                                text = { Text(if (on) "Auto-sync every ${AutoSyncScheduler.PERIOD_MINUTES} min: on" else "Auto-sync every ${AutoSyncScheduler.PERIOD_MINUTES} min: off") },
                                leadingIcon = { Icon(if (on) Icons.Rounded.SyncLock else Icons.Rounded.Sync, contentDescription = null) },
                                trailingIcon = { Switch(checked = on, onCheckedChange = null) },
                                onClick = { menu = false; withNotificationPermission { viewModel.setAutoSync(!on) } },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Share archive as zip") },
                            leadingIcon = { Icon(Icons.Rounded.IosShare, contentDescription = null) },
                            enabled = hasFiles,
                            onClick = { menu = false; viewModel.shareZip() },
                        )
                        DropdownMenuItem(
                            text = { Text("Save zip to…") },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            enabled = hasFiles,
                            onClick = { menu = false; viewModel.saveZip() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share ${state.archived.size} Parquet files") },
                            enabled = hasFiles,
                            onClick = { menu = false; viewModel.shareAllLoose() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share daily files (derived)") },
                            leadingIcon = { Icon(Icons.Rounded.IosShare, contentDescription = null) },
                            enabled = hasFiles,
                            onClick = { menu = false; viewModel.shareDailyZip() },
                        )
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                            onClick = { menu = false; viewModel.disconnect(); onDisconnected() },
                        )
                    }
                },
            )
        },
        bottomBar = {
            DeviceBottomBar(DeviceTab.FILES) {
                when (it) {
                    DeviceTab.TODAY -> onOpenToday()
                    DeviceTab.HISTORY -> onOpenHistory()
                    DeviceTab.DEVICE -> onOpenDevice()
                    DeviceTab.FILES -> Unit
                }
            }
        },
        floatingActionButton = {
            when {
                state.sync.running -> ExtendedFloatingActionButton(
                    onClick = { viewModel.cancelSync() },
                    icon = { Icon(Icons.Rounded.Stop, contentDescription = null) },
                    text = { Text("Stop") },
                )
                plan != null && plan.missingCount > 0 && state.connection.isReady -> ExtendedFloatingActionButton(
                    onClick = { withNotificationPermission { viewModel.syncAll() } },
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    text = { Text("Sync all · ${plan.missingCount} · ${formatBytes(plan.missingBytes)}") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.connection !is ConnectionState.Ready) {
                item { ConnectionBanner(state.connection, reconnecting = state.reconnecting, technical = level.developer) }
            }
            if (state.sync.running || state.sync.outcomes.isNotEmpty()) {
                item { SyncProgressCard(state.sync, level) }
            }
            // From the archive alone, so it works without a session: one column per synced file.
            if (level.detailed) {
                item(key = "file-chart") {
                    FileChartCard(
                        state = state.fileChart,
                        onToggle = viewModel::setFileChart,
                        onMetric = viewModel::setFileChartMetric,
                        onPage = viewModel::pageFileChart,
                    )
                }
            }
            // The last successful listing stays on screen through a refresh and through its failure
            // (ManifestState keeps `last`); Loading/Error only add a line above it. Without a listing
            // yet, they are the whole content.
            val manifestState = state.manifest
            val m = manifestState.last
            when {
                manifestState is ManifestState.Idle -> item { Hint(stringResource(R.string.files_connect_to_list)) }
                manifestState.isLoading && m == null -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.files_listing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                manifestState is ManifestState.Error && m == null -> item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Text(if (level.detailed) "LIST failed. ${manifestState.message}" else stringResource(R.string.files_list_failed_simple), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            if (m != null) {
                item {
                    // The summary takes the remaining width and wraps; the caption keeps its own
                    // slot so the two never run into each other (seen as "…GiBlisted 2 min ago").
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            if (level.developer) {
                                "${m.end.count} finalized files · ${m.end.partials} .partial · ${formatBytes(m.end.sdUsedKib * 1024)} of ${formatBytes(m.end.sdKib * 1024)}"
                            } else {
                                pluralStringResource(R.plurals.scan_files, m.end.count.toInt(), m.end.count.toInt())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        if (manifestState.isLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Text("refreshing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        } else {
                            TickingText({ now -> "listed ${formatAgo(now - m.atEpochMs)}" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    manifestState.error?.let { reason ->
                        // The refresh failed; the list below is the one from `listed … ago` and is
                        // still valid (device files are immutable). Refresh, or wait for the reconnect.
                        Text(
                            "Refresh failed: $reason — showing the last listing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (level.developer && m.files.size != m.end.count) {
                        Text(
                            "Device announced ${m.end.count} files but sent ${m.files.size} FILE frames.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (plan != null && plan.entries.isNotEmpty() && plan.missingCount == 0) {
                    item(key = "all-synced") {
                        MascotGuide(title = stringResource(R.string.files_all_synced_title), body = stringResource(R.string.files_all_synced_body))
                    }
                }
                if (m.files.isEmpty()) item { Hint(stringResource(if (level.detailed) R.string.files_none_detailed else R.string.files_none_simple)) }
                state.groups.forEach { group ->
                    item(key = "g:" + group.title + group.subtitle) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${group.entries.count { it.entry.state == FileSyncState.SYNCED }}/${group.entries.size} · ${formatBytes(group.bytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            group.subtitle?.takeIf { level.developer }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(group.entries, key = { it.entry.name }) { item ->
                        val entry = item.entry
                        FileRow(
                            entry = entry,
                            localTime = item.localTime,
                            showName = level.developer || item.localTime == null,
                            canSync = state.connection.isReady && !state.sync.running,
                            onSync = { withNotificationPermission { viewModel.syncOne(entry.name) } },
                            onShare = {
                                viewModel.archivedFor(entry.name)
                                    ?.let(viewModel::share)
                            },
                            onInspect = if (state.advancedMode) {
                                {
                                    viewModel.archivedFor(entry.name)
                                        ?.let { onOpenFileMetrics(it.station, it.name) }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SyncProgressCard(sync: SyncProgress, level: DetailLevel) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (sync.running) "syncing" else "last sync", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.weight(1f))
                if (sync.running) {
                    if (level.detailed) Text(formatRate(sync.bytesPerSecond), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    sync.finishedAtEpochMs?.let { done ->
                        TickingText({ now -> formatAgo(now - done) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${sync.filesDone}", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(" / ${sync.filesTotal} files", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(sync.bytesDone)} of ${formatBytes(sync.bytesTotal)}" +
                    if (level.developer && sync.windowsRecovered > 0) " · recovered ${sync.windowsRecovered} lost window${if (sync.windowsRecovered == 1) "" else "s"}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            sync.current?.takeIf { level.detailed }?.let { cur ->
                Spacer(Modifier.height(8.dp))
                if (level.developer) Text(cur.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                LinearProgressIndicator(progress = { cur.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    "${formatBytes(cur.bytesDone)} / ${formatBytes(cur.bytesTotal)}" + if (level.developer && cur.resumedFrom > 0) " · resumed from ${formatBytes(cur.resumedFrom)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (!sync.running && sync.failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(pluralStringResource(R.plurals.sync_some_failed, sync.failures.size, sync.failures.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FileRow(entry: ManifestEntry, localTime: String?, showName: Boolean, canSync: Boolean, onSync: () -> Unit, onShare: () -> Unit, onInspect: (() -> Unit)? = null) {
    val (icon, tint, label) = when (entry.state) {
        FileSyncState.SYNCED -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondary, "synced and checked")
        FileSyncState.SYNCING -> Triple(null, MaterialTheme.colorScheme.primary, "syncing ${formatBytes(entry.partialBytes)} / ${formatBytes(entry.bytes)}")
        FileSyncState.PARTIAL -> Triple(Icons.Rounded.Download, MaterialTheme.colorScheme.tertiary, "partial ${formatBytes(entry.partialBytes)} · resumable")
        FileSyncState.NOT_SYNCED -> Triple(Icons.Rounded.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, "not synced")
        FileSyncState.MISMATCH -> Triple(Icons.Rounded.Error, MaterialTheme.colorScheme.error, "mismatch")
        FileSyncState.INVALID_NAME -> Triple(Icons.Rounded.Error, MaterialTheme.colorScheme.error, "invalid name")
    }
    val clickable = when (entry.state) {
        FileSyncState.SYNCED -> true
        FileSyncState.NOT_SYNCED, FileSyncState.PARTIAL, FileSyncState.MISMATCH -> canSync
        else -> false
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (entry.state == FileSyncState.SYNCING) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickable) { if (entry.state == FileSyncState.SYNCED) onShare() else onSync() },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (showName) entry.name.substringAfterLast('/') else localTime.orEmpty(),
                        style = if (showName) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        fontFamily = if (showName) FontFamily.Monospace else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The name's HHMM is the UTC window start; the phone's local time goes first so the two never get confused.
                    Text(
                        (localTime?.takeIf { showName }?.let { "$it · " } ?: "") + "${formatBytes(entry.bytes)} · $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.state == FileSyncState.MISMATCH || entry.state == FileSyncState.INVALID_NAME) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.state == FileSyncState.SYNCED) {
                    // Advanced mode: open the file in the app's Parquet reader (rows, time span, every column).
                    if (onInspect != null) {
                        IconButton(onClick = onInspect, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.TableChart, contentDescription = "Metrics", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(Icons.Rounded.IosShare, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (entry.state == FileSyncState.SYNCING) {
                LinearProgressIndicator(
                    progress = { if (entry.bytes == 0L) 0f else (entry.partialBytes.toFloat() / entry.bytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            entry.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

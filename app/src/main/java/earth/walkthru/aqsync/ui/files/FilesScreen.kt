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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ManifestState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.formatBytes
import earth.walkthru.aqsync.ui.components.formatRate
import earth.walkthru.aqsync.ui.components.rememberNowMillis

@Composable
fun FilesScreen(
    address: String,
    onOpenToday: () -> Unit,
    onOpenDevice: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val now = rememberNowMillis()

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onDocumentCreated(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
                is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { fx ->
            when (fx) {
                is FilesEffect.Launch -> context.startActivity(fx.intent)
                is FilesEffect.CreateDocument -> createDoc.launch(fx.suggestedName)
            }
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
                        Text(
                            "${state.archived.size} in archive · ${formatBytes(state.archivedBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = state.connection.isReady && state.manifest !is ManifestState.Loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh list")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Export") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val hasFiles = state.archived.isNotEmpty() && !state.exporting
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
                    onClick = { viewModel.syncAll() },
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
                item { ConnectionBanner(state.connection) }
            }
            if (state.sync.running || state.sync.outcomes.isNotEmpty()) {
                item { SyncProgressCard(state.sync, now) }
            }
            when (val m = state.manifest) {
                ManifestState.Idle -> item { Hint("Connect to list the files on the card.") }
                ManifestState.Loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Listing files on the microSD…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ManifestState.Error -> item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Text("LIST failed: ${m.message}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                is ManifestState.Loaded -> {
                    item {
                        // The summary takes the remaining width and wraps; the caption keeps its own
                        // slot so the two never run into each other (seen as "…GiBlisted 2 min ago").
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                "${m.end.count} finalized files · ${m.end.partials} .partial · ${formatBytes(m.end.sdUsedKib * 1024)} of ${formatBytes(m.end.sdKib * 1024)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "listed ${formatAgo(now - m.atEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (m.files.size != m.end.count) {
                            Text(
                                "Device announced ${m.end.count} files but sent ${m.files.size} FILE frames.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (plan != null && plan.entries.isNotEmpty() && plan.missingCount == 0) {
                        item { Hint("Everything on the card is in the archive and verified.") }
                    }
                    if (m.files.isEmpty()) item { Hint("No finalized Parquet files yet. Use \"Finalize buffered rows\" on Today to create one.") }
                    state.groups.forEach { group ->
                        item(key = "g:" + group.title + group.subtitle) {
                            Column(Modifier.padding(top = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${group.entries.count { it.state == FileSyncState.SYNCED }}/${group.entries.size} · ${formatBytes(group.bytes)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                group.subtitle?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(group.entries, key = { it.name }) { entry ->
                            FileRow(
                                entry = entry,
                                canSync = state.connection.isReady && !state.sync.running,
                                onSync = { viewModel.syncOne(entry.name) },
                                onShare = {
                                    viewModel.archivedFor(entry.name, (entry.partition as? earth.walkthru.aqsync.data.sync.HivePartition.Dated)?.station)
                                        ?.let(viewModel::share)
                                },
                            )
                        }
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
private fun SyncProgressCard(sync: SyncProgress, now: Long) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (sync.running) "syncing" else "last sync", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.weight(1f))
                if (sync.running) {
                    Text(formatRate(sync.bytesPerSecond), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else sync.finishedAtEpochMs?.let {
                    Text(formatAgo(now - it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
                "${formatBytes(sync.bytesDone)} of ${formatBytes(sync.bytesTotal)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            sync.current?.let { cur ->
                Spacer(Modifier.height(8.dp))
                Text(cur.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                LinearProgressIndicator(progress = { cur.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    "${formatBytes(cur.bytesDone)} / ${formatBytes(cur.bytesTotal)}" + if (cur.resumedFrom > 0) " · resumed from ${formatBytes(cur.resumedFrom)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (!sync.running && sync.failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("${sync.failures.size} file(s) failed — see rows marked below", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FileRow(entry: ManifestEntry, canSync: Boolean, onSync: () -> Unit, onShare: () -> Unit) {
    val (icon, tint, label) = when (entry.state) {
        FileSyncState.SYNCED -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary, "synced · verified")
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
                        entry.name.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(entry.bytes)} · $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.state == FileSyncState.MISMATCH || entry.state == FileSyncState.INVALID_NAME) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.state == FileSyncState.SYNCED) {
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

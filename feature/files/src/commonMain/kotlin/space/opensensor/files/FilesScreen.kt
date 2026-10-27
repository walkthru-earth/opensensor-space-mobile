package space.opensensor.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ManifestState
import space.opensensor.data.SyncProgress
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.ManifestEntry
import space.opensensor.data.sync.SyncPlan
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.FloatingTextButton
import space.opensensor.designsystem.MascotGuide
import space.opensensor.designsystem.OutlinePillButton
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.PeacockIcon
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.rememberNowMillis
import space.opensensor.designsystem.spokenList
import space.opensensor.designsystem.touchTarget
import space.opensensor.files.resources.Res
import space.opensensor.files.resources.action_back
import space.opensensor.files.resources.ago_days
import space.opensensor.files.resources.ago_hours
import space.opensensor.files.resources.ago_just_now
import space.opensensor.files.resources.ago_minutes
import space.opensensor.files.resources.ago_seconds
import space.opensensor.files.resources.file_invalid
import space.opensensor.files.resources.file_metrics_named
import space.opensensor.files.resources.file_mismatch
import space.opensensor.files.resources.file_not_synced
import space.opensensor.files.resources.file_partial
import space.opensensor.files.resources.file_removed
import space.opensensor.files.resources.file_share_named
import space.opensensor.files.resources.file_sync_named
import space.opensensor.files.resources.file_synced
import space.opensensor.files.resources.file_syncing
import space.opensensor.files.resources.files_all_synced_body
import space.opensensor.files.resources.files_all_synced_title
import space.opensensor.files.resources.files_bytes_of
import space.opensensor.files.resources.files_dev_summary
import space.opensensor.files.resources.files_empty_body
import space.opensensor.files.resources.files_empty_title
import space.opensensor.files.resources.files_export_failed
import space.opensensor.files.resources.files_export_no_space
import space.opensensor.files.resources.files_export_skipped
import space.opensensor.files.resources.files_frame_mismatch
import space.opensensor.files.resources.files_group_benchmarks
import space.opensensor.files.resources.files_group_boot
import space.opensensor.files.resources.files_group_count
import space.opensensor.files.resources.files_group_legacy
import space.opensensor.files.resources.files_group_root
import space.opensensor.files.resources.files_group_thing
import space.opensensor.files.resources.files_group_unrecognised
import space.opensensor.files.resources.files_group_unsynced
import space.opensensor.files.resources.files_group_utc_day
import space.opensensor.files.resources.files_group_utc_folders
import space.opensensor.files.resources.files_in_archive
import space.opensensor.files.resources.files_last_sync
import space.opensensor.files.resources.files_list_failed_detail
import space.opensensor.files.resources.files_list_failed_simple
import space.opensensor.files.resources.files_listed
import space.opensensor.files.resources.files_listing
import space.opensensor.files.resources.files_more
import space.opensensor.files.resources.files_no_daily
import space.opensensor.files.resources.files_none_detailed
import space.opensensor.files.resources.files_none_simple
import space.opensensor.files.resources.files_of_total
import space.opensensor.files.resources.files_recovered
import space.opensensor.files.resources.files_refresh
import space.opensensor.files.resources.files_refresh_failed
import space.opensensor.files.resources.files_refresh_failed_simple
import space.opensensor.files.resources.files_refreshing
import space.opensensor.files.resources.files_resumed
import space.opensensor.files.resources.files_retry
import space.opensensor.files.resources.files_save_zip
import space.opensensor.files.resources.files_share_all
import space.opensensor.files.resources.files_share_daily
import space.opensensor.files.resources.files_share_zip
import space.opensensor.files.resources.files_stop
import space.opensensor.files.resources.files_sync_all
import space.opensensor.files.resources.files_syncing
import space.opensensor.files.resources.files_title
import space.opensensor.files.resources.scan_files
import space.opensensor.settings.DetailLevel
import space.opensensor.strings.resources.sync_some_failed
import space.opensensor.strings.resources.Res as SharedRes

/** What a connected sensor adds to the screen, its listing and the sync. Android only for now. */
data class DeviceFiles(
    val ready: Boolean,
    val manifest: ManifestState,
    val plan: SyncPlan?,
    val sync: SyncProgress,
)

/** Requests the screen sends to the device session. */
sealed interface DeviceFilesAction {
    data object Refresh : DeviceFilesAction
    data object SyncAll : DeviceFilesAction
    data class SyncOne(val name: String) : DeviceFilesAction
    data object CancelSync : DeviceFilesAction
}

/** The extra rows a platform puts in the overflow menu. [close] dismisses the menu. */
typealias MenuItems = @Composable ColumnScope.(close: () -> Unit) -> Unit

/**
 * The Files screen for Android and iOS (docs/screens.md, Files). Without a [device] it lists the phone
 * archive, grouped by local day, with the inspector and every export. With one it shows the sensor's
 * listing, each file's sync state, and the sync controls. Platform pieces (the link banner, section
 * tabs, the chart, background sync) come in through the slots.
 */
@Composable
fun FilesScreen(
    controller: FilesController,
    level: DetailLevel,
    onOpenFileMetrics: (thing: String, name: String) -> Unit,
    device: DeviceFiles? = null,
    onDeviceAction: (DeviceFilesAction) -> Unit = {},
    onBack: (() -> Unit)? = null,
    /** Wraps a sync start, Android asks for the notification permission there. */
    guard: (() -> Unit) -> Unit = { it() },
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    subtitleLead: @Composable () -> Unit = {},
    tabs: @Composable () -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
    chart: (@Composable () -> Unit)? = null,
    /** Shown when there is no device listing, before the archive groups. */
    idleHint: String? = null,
    menuLead: MenuItems? = null,
    menuTail: MenuItems? = null,
    /** Live transfer bytes collected only by the progress card. Without it, [device] supplies the snapshot. */
    progress: StateFlow<SyncProgress>? = null,
) {
    val state by controller.state.collectAsState()
    val presenter = rememberExportPresenter()
    val zone = remember { TimeZone.currentSystemDefault() }

    LaunchedEffect(controller) {
        controller.messages.collect { m ->
            snackbar.showSnackbar(
                when (m) {
                    FilesMessage.NoDaily -> getString(Res.string.files_no_daily)
                    FilesMessage.ExportFailed -> getString(Res.string.files_export_failed)
                    FilesMessage.NoSpace -> getString(Res.string.files_export_no_space)
                    is FilesMessage.Skipped -> getPluralString(Res.plurals.files_export_skipped, m.count, m.count)
                },
                // Android's chooser returns at once, so the count stays up while it is open.
                duration = if (m is FilesMessage.Skipped) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    val plan = device?.plan
    val manifest = device?.manifest ?: ManifestState.Idle
    val listing = manifest.last
    val ready = device?.ready == true
    val sync = device?.sync ?: SyncProgress()
    val groups = remember(plan, listing, state.records, state.owner, zone) {
        if (listing != null && plan != null) {
            FileGrouping.group(plan.entries.map { it to state.owner }, zone)
        } else {
            FileGrouping.group(FilesController.archiveItems(state.shown), zone)
        }
    }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(Res.string.files_title), modifier = Modifier.semantics { heading() })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                subtitleLead()
                                Text(
                                    stringResource(Res.string.files_in_archive, state.shown.size, formatBytes(state.archivedBytes)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back)) }
                        }
                    },
                    actions = {
                        if (device != null) {
                            IconButton(onClick = { onDeviceAction(DeviceFilesAction.Refresh) }, enabled = ready && !manifest.isLoading) {
                                Icon(AppIcons.Refresh, contentDescription = stringResource(Res.string.files_refresh))
                            }
                        }
                        IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, contentDescription = stringResource(Res.string.files_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val close = { menu = false }
                            val hasFiles = state.shown.isNotEmpty() && !state.exporting
                            if (menuLead != null) {
                                menuLead(close)
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.files_share_zip)) },
                                leadingIcon = { Icon(AppIcons.IosShare, contentDescription = null) },
                                enabled = hasFiles,
                                onClick = { close(); controller.shareZip(presenter) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.files_save_zip)) },
                                leadingIcon = { Icon(AppIcons.Download, contentDescription = null) },
                                enabled = hasFiles,
                                onClick = { close(); controller.saveZip(presenter) },
                            )
                            DropdownMenuItem(
                                text = { Text(pluralStringResource(Res.plurals.files_share_all, state.shown.size, state.shown.size)) },
                                enabled = hasFiles,
                                onClick = { close(); controller.shareAll(presenter) },
                            )
                            if (state.dailyAvailable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.files_share_daily)) },
                                    leadingIcon = { Icon(AppIcons.IosShare, contentDescription = null) },
                                    enabled = hasFiles,
                                    onClick = { close(); controller.shareDaily(presenter) },
                                )
                            }
                            menuTail?.invoke(this, close)
                        }
                    },
                )
                tabs()
            }
        },
        floatingActionButton = {
            when {
                sync.running -> FloatingTextButton(
                    text = stringResource(Res.string.files_stop),
                    onClick = { onDeviceAction(DeviceFilesAction.CancelSync) },
                    primary = false,
                    icon = { Icon(AppIcons.Stop, contentDescription = null) },
                )
                plan != null && plan.missingCount > 0 && ready -> FloatingTextButton(
                    text = pluralStringResource(Res.plurals.files_sync_all, plan.missingCount, plan.missingCount, formatBytes(plan.missingBytes)),
                    onClick = { guard { onDeviceAction(DeviceFilesAction.SyncAll) } },
                    icon = { Icon(AppIcons.Download, contentDescription = null) },
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
            if (banner != null) item(key = "banner") { banner() }
            if (sync.running || sync.outcomes.isNotEmpty()) {
                item(key = "sync") {
                    if (progress == null) SyncProgressCard(sync, level) else SyncProgressCard(progress, level)
                }
            }
            if (chart != null && level.detailed) item(key = "file-chart") { chart() }
            // The last successful listing stays on screen through a refresh and through its failure
            // (ManifestState keeps `last`), Loading and Error only add a line above it.
            when {
                manifest is ManifestState.Idle -> idleHint?.let { item(key = "hint") { Hint(it) } }
                manifest.isLoading && listing == null -> item(key = "listing") {
                    // Read as one polite announcement while the sensor lists its files.
                    Row(
                        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.files_listing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                manifest is ManifestState.Error && listing == null -> item(key = "list-failed") {
                    ListFailedCard(manifest.message, level, ready, onDeviceAction)
                }
            }
            if (listing == null && groups.isEmpty() && idleHint == null && manifest is ManifestState.Idle) {
                item(key = "archive-empty") {
                    MascotGuide(title = stringResource(Res.string.files_empty_title), body = stringResource(Res.string.files_empty_body))
                }
            }
            if (listing != null) {
                item(key = "summary") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        val count = listing.end.count
                        val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        Text(
                            if (level.developer) {
                                pluralStringResource(Res.plurals.files_dev_summary, quantity, count, listing.end.partials, formatBytes(listing.end.sdUsedKib * 1024), formatBytes(listing.end.sdKib * 1024))
                            } else {
                                pluralStringResource(Res.plurals.scan_files, quantity, count)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        if (manifest.isLoading) {
                            Row(
                                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                FitText(stringResource(Res.string.files_refreshing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            FitText(
                                stringResource(Res.string.files_listed, ago(listing.atEpochMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    manifest.error?.let { reason ->
                        // Keep immutable known files visible, but this snapshot may omit newer files.
                        Text(
                            if (level.developer) stringResource(Res.string.files_refresh_failed, reason) else stringResource(Res.string.files_refresh_failed_simple),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (level.developer && listing.files.size.toLong() != listing.end.count) {
                        val count = listing.end.count
                        Text(
                            pluralStringResource(Res.plurals.files_frame_mismatch, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count, listing.files.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (manifest.confirmsAllSynced(plan)) {
                    item(key = "all-synced") {
                        MascotGuide(title = stringResource(Res.string.files_all_synced_title), body = stringResource(Res.string.files_all_synced_body), expression = PeacockExpression.HAPPY)
                    }
                }
                if (listing.files.isEmpty()) item(key = "none") { Hint(stringResource(if (level.detailed) Res.string.files_none_detailed else Res.string.files_none_simple)) }
            }
            groups.forEach { group ->
                item(key = "g:" + group.key) { GroupHeader(group, level) }
                items(group.entries, key = { it.key }) { item ->
                    val entry = item.entry
                    val record = if (listing != null) controller.archived(entry.name) else state.records.firstOrNull { it.thing == item.thing && it.name == entry.name }
                    FileRow(
                        entry = entry,
                        localTime = item.localTime,
                        showName = level.developer || item.localTime == null,
                        showDetail = level.developer,
                        canSync = ready && !sync.running,
                        onSync = { guard { onDeviceAction(DeviceFilesAction.SyncOne(entry.name)) } },
                        onShare = { record?.let { controller.shareFile(it, presenter) } },
                        onInspect = if (level.detailed && record != null) {
                            { onOpenFileMetrics(record.thing, record.name) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** The screen title, for a platform control that opens [FilesScreen]. */
@Composable
fun filesTitle(): String = stringResource(Res.string.files_title)

@Composable
private fun Hint(text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PeacockIcon(PeacockExpression.CONFUSED, Modifier.size(48.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** How long ago [epochMs] was, refreshed every second. */
@Composable
private fun ago(epochMs: Long): String {
    val ageMs = rememberNowMillis() - epochMs
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> stringResource(Res.string.ago_just_now)
        s < 60 -> stringResource(Res.string.ago_seconds, s)
        s < 3600 -> stringResource(Res.string.ago_minutes, s / 60)
        s < 86_400 -> stringResource(Res.string.ago_hours, s / 3600)
        else -> stringResource(Res.string.ago_days, s / 86_400)
    }
}

@Composable
private fun GroupHeader(group: PartitionGroup, level: DetailLevel) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(groupTitle(group.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.files_group_count, group.synced, group.entries.size, formatBytes(group.bytes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (level.developer) {
            groupDetail(group.detail)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SyncProgressCard(progress: StateFlow<SyncProgress>, level: DetailLevel) {
    val sync by progress.collectAsState()
    SyncProgressCard(sync, level)
}

@Composable
private fun SyncProgressCard(sync: SyncProgress, level: DetailLevel) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            val on = MaterialTheme.colorScheme.onPrimaryContainer
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeacockIcon(
                    when {
                        sync.running -> PeacockExpression.WORKING
                        sync.failures.isNotEmpty() -> PeacockExpression.CONCERNED
                        else -> PeacockExpression.HAPPY
                    },
                    Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Eyebrow(stringResource(if (sync.running) Res.string.files_syncing else Res.string.files_last_sync), modifier = Modifier.weight(1f), color = on, heading = true)
                Spacer(Modifier.width(8.dp))
                if (sync.running) {
                    if (level.detailed) Text(formatRate(sync.bytesPerSecond), style = MaterialTheme.typography.labelMedium, color = on)
                } else {
                    sync.finishedAtEpochMs?.let { done -> Text(ago(done), style = MaterialTheme.typography.labelMedium, color = on) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // "3 of 12 files" as one element, announced politely while the sync runs.
            Row(
                Modifier.semantics(mergeDescendants = true) { if (sync.running) liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("${sync.filesDone}", style = MaterialTheme.typography.displayMedium, color = on)
                Text(" " + pluralStringResource(Res.plurals.files_of_total, sync.filesTotal, sync.filesTotal), style = MaterialTheme.typography.titleMedium, color = on, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            val recovered = if (level.developer && sync.windowsRecovered > 0) " · " + pluralStringResource(Res.plurals.files_recovered, sync.windowsRecovered, sync.windowsRecovered) else ""
            Text(stringResource(Res.string.files_bytes_of, formatBytes(sync.bytesDone), formatBytes(sync.bytesTotal)) + recovered, style = MaterialTheme.typography.bodySmall, color = on)
            sync.current?.takeIf { level.detailed }?.let { cur ->
                Spacer(Modifier.height(8.dp))
                if (level.developer) Text(cur.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = on)
                LinearProgressIndicator(progress = { cur.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                val resumed = if (level.developer && cur.resumedFrom > 0) " · " + stringResource(Res.string.files_resumed, formatBytes(cur.resumedFrom)) else ""
                Text(stringResource(Res.string.files_bytes_of, formatBytes(cur.bytesDone), formatBytes(cur.bytesTotal)) + resumed, style = MaterialTheme.typography.labelSmall, color = on)
            }
            if (!sync.running && sync.failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(pluralStringResource(SharedRes.plurals.sync_some_failed, sync.failures.size, sync.failures.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * The card shown when the sensor could not list its files, announced once, with Try again right
 * under the message, which sends [DeviceFilesAction.Refresh]. Try again shows only while the sensor
 * link is [ready], without a link the header already says to connect.
 */
@Composable
internal fun ListFailedCard(message: String, level: DetailLevel, ready: Boolean, onDeviceAction: (DeviceFilesAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PeacockIcon(PeacockExpression.CONCERNED, Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyText(
                    if (level.developer) stringResource(Res.string.files_list_failed_detail, message) else stringResource(Res.string.files_list_failed_simple),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (ready) OutlinePillButton(stringResource(Res.string.files_retry), onClick = { onDeviceAction(DeviceFilesAction.Refresh) })
            }
        }
    }
}

@Composable
internal fun FileRow(entry: ManifestEntry, localTime: String?, showName: Boolean, showDetail: Boolean, canSync: Boolean, onSync: () -> Unit, onShare: () -> Unit, onInspect: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, label) = when (entry.state) {
        FileSyncState.SYNCED -> Triple(AppIcons.CheckCircle, scheme.secondary, stringResource(Res.string.file_synced))
        FileSyncState.SYNCING -> Triple(null, scheme.primary, stringResource(Res.string.file_syncing, formatBytes(entry.partialBytes), formatBytes(entry.bytes)))
        FileSyncState.PARTIAL -> Triple(AppIcons.Download, scheme.tertiary, stringResource(Res.string.file_partial, formatBytes(entry.partialBytes)))
        FileSyncState.NOT_SYNCED -> Triple(AppIcons.CloudOff, scheme.onSurfaceVariant, stringResource(Res.string.file_not_synced))
        FileSyncState.MISMATCH -> Triple(AppIcons.Error, scheme.error, stringResource(Res.string.file_mismatch))
        FileSyncState.INVALID_NAME -> Triple(AppIcons.Error, scheme.error, stringResource(Res.string.file_invalid))
        FileSyncState.REMOVED -> Triple(AppIcons.CloudOff, scheme.onSurfaceVariant, stringResource(Res.string.file_removed))
    }
    // Each action is its own 48 dp button that names the file it acts on, the row itself does nothing on tap.
    val which = localTime ?: entry.name.substringAfterLast('/')
    val syncable = canSync && entry.state in SYNCABLE
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (entry.state == FileSyncState.SYNCING) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    // The state is spoken from the text line below, the icon only repeats it.
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    FitText(
                        if (showName) entry.name.substringAfterLast('/') else localTime.orEmpty(),
                        style = if (showName) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        fontFamily = if (showName) FontFamily.Monospace else null,
                        maxLines = 1,
                    )
                    // The name's HHMM is the UTC window start. The phone's local time goes first so the two never get confused.
                    BodyText(
                        (localTime?.takeIf { showName }?.let { "$it · " } ?: "") + "${formatBytes(entry.bytes)} · $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.state == FileSyncState.MISMATCH || entry.state == FileSyncState.INVALID_NAME) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                if (entry.state == FileSyncState.SYNCED) {
                    if (onInspect != null) {
                        RowAction(AppIcons.TableChart, stringResource(Res.string.file_metrics_named, which), onInspect)
                    }
                    RowAction(AppIcons.IosShare, stringResource(Res.string.file_share_named, which), onShare)
                }
                if (syncable) {
                    RowAction(AppIcons.Download, stringResource(Res.string.file_sync_named, which), onSync)
                }
            }
            if (entry.state == FileSyncState.SYNCING) {
                LinearProgressIndicator(
                    progress = { if (entry.bytes == 0L) 0f else (entry.partialBytes.toFloat() / entry.bytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            entry.detail?.takeIf { showDetail }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
            }
        }
    }
}

/** The states a file row offers to sync, a fresh file, a partial copy, or one whose checksum did not match. */
private val SYNCABLE = setOf(FileSyncState.NOT_SYNCED, FileSyncState.PARTIAL, FileSyncState.MISMATCH)

/** One compact action on a file row, a 48 dp target around a 20 dp icon, spoken by [label]. */
@Composable
private fun RowAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.touchTarget()) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun groupTitle(title: GroupTitle): String = when (title) {
    is GroupTitle.LocalDay -> remember(title.date) { formatLocalDay(title.date) }
    is GroupTitle.UtcDay -> stringResource(Res.string.files_group_utc_day, title.isoDate)
    GroupTitle.Unsynced -> stringResource(Res.string.files_group_unsynced)
    GroupTitle.Benchmarks -> stringResource(Res.string.files_group_benchmarks)
    GroupTitle.Legacy -> stringResource(Res.string.files_group_legacy)
    is GroupTitle.Other -> title.directory.ifEmpty { stringResource(Res.string.files_group_root) }
}

/** Developer line under a group header, null when there is nothing to show. */
@Composable
private fun groupDetail(detail: GroupDetail): String? = listOfNotNull(
    detail.thing?.let { stringResource(Res.string.files_group_thing, it) },
    detail.utcFolders.takeIf { it.isNotEmpty() }?.let { pluralStringResource(Res.plurals.files_group_utc_folders, it.size, spokenList(it, listSeparator())) },
    detail.boot?.let { stringResource(Res.string.files_group_boot, it) },
    stringResource(Res.string.files_group_unrecognised).takeIf { detail.unrecognised },
).joinToString(" · ").ifEmpty { null }

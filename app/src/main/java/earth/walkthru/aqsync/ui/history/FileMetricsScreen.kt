package earth.walkthru.aqsync.ui.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.formatBytes
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.Eyebrow
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.SectionCard
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.WarningNote
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** `event_time_utc_ns` → `2026-09-08 19:45:10Z`. */
fun formatUtcNs(ns: Long): String = UTC_FORMAT.format(Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)))

private fun formatMonoUs(us: Long): String {
    val s = us / 1_000_000
    return if (s >= 3600) "%dh %02dm %02ds".format(java.util.Locale.ROOT, s / 3600, s % 3600 / 60, s % 60) else "%dm %02ds".format(java.util.Locale.ROOT, s / 60, s % 60)
}

/**
 * Detailed/Developer view of one archived file, decoded by the app's own Parquet reader: footer
 * facts, row groups with their UTC spans (from v6 statistics), and every column's decoded summary.
 * Purely descriptive. Nothing here changes the archive.
 */
@Composable
fun FileMetricsScreen(
    station: String,
    name: String,
    onBack: () -> Unit,
    viewModel: FileMetricsViewModel = hiltViewModel(),
) {
    LaunchedEffect(station, name) { viewModel.load(station, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val level by viewModel.detailLevel.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        when (val s = state) {
            FileMetricsState.Loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            is FileMetricsState.Failed -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                WarningNote(
                    stringResource(
                        when (s.reason) {
                            FailReason.NOT_IN_ARCHIVE -> R.string.fm_failed_missing
                            FailReason.READER_REFUSED -> R.string.fm_failed_refused
                            FailReason.UNREADABLE -> R.string.fm_failed_unreadable
                        },
                    ),
                )
                if (level.developer) s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (s.reason != FailReason.NOT_IN_ARCHIVE) InfoNote(stringResource(R.string.fm_failed_archive_safe))
            }
            is FileMetricsState.Loaded -> Loaded(s, padding, level.developer)
        }
    }
}

@Composable
private fun Loaded(s: FileMetricsState.Loaded, padding: PaddingValues, developer: Boolean) {
    val idx = s.index
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "file") {
            SectionCard(stringResource(R.string.fm_file)) {
                Text(s.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                StatLine(
                    stringResource(R.string.fm_size),
                    if (developer) stringResource(R.string.fm_size_footer, formatBytes(s.sizeBytes), formatBytes(s.footerBytes.toLong())) else formatBytes(s.sizeBytes),
                )
                StatLine(
                    stringResource(R.string.fm_rows),
                    if (developer) idx.rows.toString() + " · " + pluralStringResource(R.plurals.row_groups, idx.rowGroups, idx.rowGroups) else idx.rows.toString(),
                )
                StatLine(
                    stringResource(R.string.fm_columns),
                    if (developer) stringResource(R.string.fm_columns_codec, idx.columns, s.codec?.toString() ?: NO_VALUE) else idx.columns.toString(),
                )
                if (developer) {
                    StatLine(stringResource(R.string.fm_schema), idx.schemaVersion ?: NO_VALUE)
                    StatLine(stringResource(R.string.fm_writer), idx.createdBy ?: NO_VALUE)
                    StatLine(stringResource(R.string.fm_statistics), stringResource(if (s.columnOrders > 0) R.string.fm_statistics_minmax else R.string.fm_statistics_nulls))
                    StatLine(stringResource(R.string.fm_index_source), stringResource(if (idx.fromStatistics) R.string.fm_index_footer else R.string.fm_index_rows))
                    StatLine(stringResource(R.string.fm_decode_time), stringResource(R.string.fm_decode_ms, s.decodeMillis))
                }
                if (!idx.isTelemetry) {
                    Spacer(Modifier.height(8.dp))
                    WarningNote(stringResource(R.string.fm_benchmark, idx.purpose ?: NO_VALUE))
                }
            }
        }
        item(key = "time") {
            SectionCard(stringResource(R.string.fm_time)) {
                if (idx.hasUtc) {
                    StatLine(stringResource(R.string.fm_utc_rows), stringResource(R.string.history_of, idx.utcRows, idx.rows))
                    StatLine(stringResource(R.string.fm_first), idx.firstUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                    StatLine(stringResource(R.string.fm_last), idx.lastUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                } else {
                    StatLine(stringResource(R.string.fm_utc), stringResource(R.string.fm_utc_none))
                }
                StatLine(stringResource(R.string.fm_valid_pm_rows), stringResource(R.string.history_of, idx.pmValidRows, idx.rows))
                if (developer) {
                    StatLine(stringResource(R.string.fm_uptime_span), "${idx.firstMonoUs?.let(::formatMonoUs) ?: NO_VALUE} → ${idx.lastMonoUs?.let(::formatMonoUs) ?: NO_VALUE}")
                    StatLine(stringResource(R.string.fm_sequence), "${idx.firstSeq ?: NO_VALUE} → ${idx.lastSeq ?: NO_VALUE}")
                    StatLine(stringResource(R.string.fm_boot), idx.bootId?.take(12)?.plus("…") ?: NO_VALUE)
                }
            }
        }
        if (developer && s.rowGroups.size > 1) {
            item(key = "groups") {
                SectionCard(stringResource(R.string.fm_row_groups)) {
                    s.rowGroups.forEach { g ->
                        StatLine(
                            "#${g.ordinal} · " + pluralStringResource(R.plurals.fm_rows_count, g.rows.toInt(), g.rows) + " · " + formatBytes(g.bytes),
                            if (g.firstUtcNs != null && g.lastUtcNs != null) "${formatUtcNs(g.firstUtcNs).substring(11)} → ${formatUtcNs(g.lastUtcNs).substring(11)}" else NO_VALUE,
                        )
                    }
                    InfoNote(stringResource(R.string.fm_row_groups_note))
                }
            }
        }
        item(key = "columns-header") {
            Column(Modifier.padding(top = 4.dp)) {
                Eyebrow(stringResource(R.string.fm_columns_decoded))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Cell(stringResource(R.string.fm_col_name), 3f)
                    Cell(stringResource(R.string.fm_col_nulls), 1f)
                    Cell(stringResource(R.string.fm_col_min), 1.4f)
                    Cell(stringResource(R.string.fm_col_max), 1.4f)
                }
            }
        }
        items(s.columns, key = { it.name }) { c ->
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Cell(c.name, 3f, mono = true)
                    Cell(if (c.nulls == 0) "·" else c.nulls.toString(), 1f)
                    Cell(c.min, 1.4f)
                    Cell(c.max, 1.4f)
                }
                if (developer) {
                    val parts = listOfNotNull(
                        c.type.name.lowercase(),
                        stringResource(R.string.fm_col_timestamp).takeIf { c.timestamp },
                        stringResource(R.string.fm_col_first_last, c.first, c.last).takeIf { c.first != NO_VALUE },
                        if (c.statsMin != null && c.statsMax != null) {
                            if (c.statsMin == c.min && c.statsMax == c.max) stringResource(R.string.fm_col_footer_agrees) else stringResource(R.string.fm_col_footer_says, c.statsMin, c.statsMax)
                        } else {
                            null
                        },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (developer) {
            item(key = "metadata") {
                SectionCard(stringResource(R.string.fm_metadata), modifier = Modifier.padding(top = 8.dp)) {
                    s.metadata.entries.sortedBy { it.key }.forEach { (k, v) ->
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, mono: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.width(4.dp))
}

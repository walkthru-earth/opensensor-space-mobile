package earth.walkthru.opensensor.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.Eyebrow
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.PeacockExpression
import earth.walkthru.opensensor.designsystem.PeacockIcon
import earth.walkthru.opensensor.designsystem.SectionCard
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.WarningNote
import earth.walkthru.opensensor.designsystem.listSeparator
import earth.walkthru.opensensor.designsystem.spokenList
import earth.walkthru.opensensor.files.resources.Res
import earth.walkthru.opensensor.files.resources.action_back
import earth.walkthru.opensensor.files.resources.fm_benchmark
import earth.walkthru.opensensor.files.resources.fm_boot
import earth.walkthru.opensensor.files.resources.fm_col_first_last
import earth.walkthru.opensensor.files.resources.fm_col_footer_agrees
import earth.walkthru.opensensor.files.resources.fm_col_footer_says
import earth.walkthru.opensensor.files.resources.fm_col_max
import earth.walkthru.opensensor.files.resources.fm_col_min
import earth.walkthru.opensensor.files.resources.fm_col_name
import earth.walkthru.opensensor.files.resources.fm_col_nulls
import earth.walkthru.opensensor.files.resources.fm_col_timestamp
import earth.walkthru.opensensor.files.resources.fm_columns
import earth.walkthru.opensensor.files.resources.fm_columns_codec
import earth.walkthru.opensensor.files.resources.fm_columns_decoded
import earth.walkthru.opensensor.files.resources.fm_decode_ms
import earth.walkthru.opensensor.files.resources.fm_decode_time
import earth.walkthru.opensensor.files.resources.fm_failed_archive_safe
import earth.walkthru.opensensor.files.resources.fm_failed_missing
import earth.walkthru.opensensor.files.resources.fm_failed_refused
import earth.walkthru.opensensor.files.resources.fm_failed_unreadable
import earth.walkthru.opensensor.files.resources.fm_file
import earth.walkthru.opensensor.files.resources.fm_first
import earth.walkthru.opensensor.files.resources.fm_index_footer
import earth.walkthru.opensensor.files.resources.fm_index_rows
import earth.walkthru.opensensor.files.resources.fm_index_source
import earth.walkthru.opensensor.files.resources.fm_last
import earth.walkthru.opensensor.files.resources.fm_loading
import earth.walkthru.opensensor.files.resources.fm_metadata
import earth.walkthru.opensensor.files.resources.fm_row_groups
import earth.walkthru.opensensor.files.resources.fm_row_groups_note
import earth.walkthru.opensensor.files.resources.fm_rows
import earth.walkthru.opensensor.files.resources.fm_rows_count
import earth.walkthru.opensensor.files.resources.fm_schema
import earth.walkthru.opensensor.files.resources.fm_sequence
import earth.walkthru.opensensor.files.resources.fm_size
import earth.walkthru.opensensor.files.resources.fm_size_footer
import earth.walkthru.opensensor.files.resources.fm_statistics
import earth.walkthru.opensensor.files.resources.fm_statistics_minmax
import earth.walkthru.opensensor.files.resources.fm_statistics_nulls
import earth.walkthru.opensensor.files.resources.fm_time
import earth.walkthru.opensensor.files.resources.fm_uptime_span
import earth.walkthru.opensensor.files.resources.fm_utc
import earth.walkthru.opensensor.files.resources.fm_utc_none
import earth.walkthru.opensensor.files.resources.fm_utc_rows
import earth.walkthru.opensensor.files.resources.fm_valid_pm_rows
import earth.walkthru.opensensor.files.resources.fm_writer
import earth.walkthru.opensensor.files.resources.history_of
import earth.walkthru.opensensor.files.resources.row_groups
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Detailed and Developer view of one archived file, decoded by the app's own Parquet reader on Android
 * and iOS. Footer facts, row groups with their UTC spans (from v6 statistics), and every column's
 * decoded summary. Purely descriptive. Nothing here changes the archive.
 */
@Composable
fun FileMetricsScreen(state: FileMetricsState, level: DetailLevel, name: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back)) } },
            )
        },
    ) { padding ->
        when (state) {
            FileMetricsState.Loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val loading = stringResource(Res.string.fm_loading)
                PeacockIcon(PeacockExpression.WORKING, Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
            }
            is FileMetricsState.Failed -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                PeacockIcon(
                    if (state.reason == FailReason.NOT_IN_ARCHIVE) PeacockExpression.CONFUSED else PeacockExpression.CONCERNED,
                    Modifier.size(72.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(12.dp))
                WarningNote(
                    stringResource(
                        when (state.reason) {
                            FailReason.NOT_IN_ARCHIVE -> Res.string.fm_failed_missing
                            FailReason.READER_REFUSED -> Res.string.fm_failed_refused
                            FailReason.UNREADABLE -> Res.string.fm_failed_unreadable
                        },
                    ),
                )
                if (level.developer) state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.reason != FailReason.NOT_IN_ARCHIVE) InfoNote(stringResource(Res.string.fm_failed_archive_safe))
            }
            is FileMetricsState.Loaded -> Loaded(state, padding, level.developer)
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
            SectionCard(stringResource(Res.string.fm_file)) {
                Text(s.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                StatLine(
                    stringResource(Res.string.fm_size),
                    if (developer) stringResource(Res.string.fm_size_footer, formatBytes(s.sizeBytes), formatBytes(s.footerBytes.toLong())) else formatBytes(s.sizeBytes),
                )
                StatLine(
                    stringResource(Res.string.fm_rows),
                    if (developer) idx.rows.toString() + " · " + pluralStringResource(Res.plurals.row_groups, idx.rowGroups, idx.rowGroups) else idx.rows.toString(),
                )
                StatLine(
                    stringResource(Res.string.fm_columns),
                    if (developer) stringResource(Res.string.fm_columns_codec, idx.columns, s.codec?.toString() ?: NO_VALUE) else idx.columns.toString(),
                )
                if (developer) {
                    StatLine(stringResource(Res.string.fm_schema), idx.schemaVersion ?: NO_VALUE)
                    StatLine(stringResource(Res.string.fm_writer), idx.createdBy ?: NO_VALUE)
                    StatLine(stringResource(Res.string.fm_statistics), stringResource(if (s.columnOrders > 0) Res.string.fm_statistics_minmax else Res.string.fm_statistics_nulls))
                    StatLine(stringResource(Res.string.fm_index_source), stringResource(if (idx.fromStatistics) Res.string.fm_index_footer else Res.string.fm_index_rows))
                    StatLine(stringResource(Res.string.fm_decode_time), stringResource(Res.string.fm_decode_ms, s.decodeMillis))
                }
                if (!idx.isTelemetry) {
                    Spacer(Modifier.height(8.dp))
                    WarningNote(stringResource(Res.string.fm_benchmark, idx.purpose ?: NO_VALUE))
                }
            }
        }
        item(key = "time") {
            SectionCard(stringResource(Res.string.fm_time)) {
                if (idx.hasUtc) {
                    StatLine(stringResource(Res.string.fm_utc_rows), stringResource(Res.string.history_of, idx.utcRows, idx.rows))
                    StatLine(stringResource(Res.string.fm_first), idx.firstUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                    StatLine(stringResource(Res.string.fm_last), idx.lastUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                } else {
                    StatLine(stringResource(Res.string.fm_utc), stringResource(Res.string.fm_utc_none))
                }
                StatLine(stringResource(Res.string.fm_valid_pm_rows), stringResource(Res.string.history_of, idx.pmValidRows, idx.rows))
                if (developer) {
                    StatLine(stringResource(Res.string.fm_uptime_span), "${idx.firstMonoUs?.let(::formatMonoUs) ?: NO_VALUE} → ${idx.lastMonoUs?.let(::formatMonoUs) ?: NO_VALUE}")
                    StatLine(stringResource(Res.string.fm_sequence), "${idx.firstSeq ?: NO_VALUE} → ${idx.lastSeq ?: NO_VALUE}")
                    StatLine(stringResource(Res.string.fm_boot), idx.bootId?.take(12)?.plus("…") ?: NO_VALUE)
                }
            }
        }
        if (developer && s.rowGroups.size > 1) {
            item(key = "groups") {
                SectionCard(stringResource(Res.string.fm_row_groups)) {
                    s.rowGroups.forEach { g ->
                        val first = g.firstUtcNs
                        val last = g.lastUtcNs
                        StatLine(
                            "#${g.ordinal} · " + pluralStringResource(Res.plurals.fm_rows_count, g.rows.toInt(), g.rows) + " · " + formatBytes(g.bytes),
                            if (first != null && last != null) "${formatUtcNs(first).substring(11)} → ${formatUtcNs(last).substring(11)}" else NO_VALUE,
                        )
                    }
                    InfoNote(stringResource(Res.string.fm_row_groups_note))
                }
            }
        }
        item(key = "columns-header") {
            Column(Modifier.padding(top = 4.dp)) {
                Eyebrow(stringResource(Res.string.fm_columns_decoded), heading = true)
                // The header cells are read inside each column row, so a screen reader skips them here.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics {}) {
                    Cell(stringResource(Res.string.fm_col_name), 3f)
                    Cell(stringResource(Res.string.fm_col_nulls), 1f)
                    Cell(stringResource(Res.string.fm_col_min), 1.4f)
                    Cell(stringResource(Res.string.fm_col_max), 1.4f)
                }
            }
        }
        items(s.columns, key = { it.name }) { c ->
            // One spoken line per column, each value after its header, "pm2_5, nulls 0, min 1.2, max 40".
            val spoken = listOf(
                c.name,
                stringResource(Res.string.fm_col_nulls) + " " + c.nulls,
                stringResource(Res.string.fm_col_min) + " " + c.min,
                stringResource(Res.string.fm_col_max) + " " + c.max,
            ).let { spokenList(it, listSeparator()) }
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                    Cell(c.name, 3f, mono = true)
                    Cell(if (c.nulls == 0) "·" else c.nulls.toString(), 1f)
                    Cell(c.min, 1.4f)
                    Cell(c.max, 1.4f)
                }
                if (developer) {
                    val statsMin = c.statsMin
                    val statsMax = c.statsMax
                    val parts = listOfNotNull(
                        c.type.name.lowercase(),
                        stringResource(Res.string.fm_col_timestamp).takeIf { c.timestamp },
                        stringResource(Res.string.fm_col_first_last, c.first, c.last).takeIf { c.first != NO_VALUE },
                        if (statsMin != null && statsMax != null) {
                            if (statsMin == c.min && statsMax == c.max) stringResource(Res.string.fm_col_footer_agrees) else stringResource(Res.string.fm_col_footer_says, statsMin, statsMax)
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
                SectionCard(stringResource(Res.string.fm_metadata), modifier = Modifier.padding(top = 8.dp)) {
                    s.metadata.entries.sortedBy { it.key }.forEach { (k, v) ->
                        // Key and value read as one element.
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, mono: Boolean = false) {
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

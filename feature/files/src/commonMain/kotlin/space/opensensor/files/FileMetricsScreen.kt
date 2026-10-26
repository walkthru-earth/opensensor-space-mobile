package space.opensensor.files

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.PeacockIcon
import space.opensensor.designsystem.SectionCard
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.WarningNote
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.spokenList
import space.opensensor.files.resources.Res
import space.opensensor.files.resources.action_back
import space.opensensor.files.resources.fm_benchmark
import space.opensensor.files.resources.fm_boot
import space.opensensor.files.resources.fm_col_first_last
import space.opensensor.files.resources.fm_col_footer_agrees
import space.opensensor.files.resources.fm_col_footer_says
import space.opensensor.files.resources.fm_col_max
import space.opensensor.files.resources.fm_col_min
import space.opensensor.files.resources.fm_col_name
import space.opensensor.files.resources.fm_col_nulls
import space.opensensor.files.resources.fm_col_timestamp
import space.opensensor.files.resources.fm_columns
import space.opensensor.files.resources.fm_columns_codec
import space.opensensor.files.resources.fm_columns_decoded
import space.opensensor.files.resources.fm_decode_ms
import space.opensensor.files.resources.fm_decode_time
import space.opensensor.files.resources.fm_failed_archive_safe
import space.opensensor.files.resources.fm_failed_missing
import space.opensensor.files.resources.fm_failed_refused
import space.opensensor.files.resources.fm_failed_unreadable
import space.opensensor.files.resources.fm_file
import space.opensensor.files.resources.fm_first
import space.opensensor.files.resources.fm_index_footer
import space.opensensor.files.resources.fm_index_rows
import space.opensensor.files.resources.fm_index_source
import space.opensensor.files.resources.fm_last
import space.opensensor.files.resources.fm_loading
import space.opensensor.files.resources.fm_metadata
import space.opensensor.files.resources.fm_row_groups
import space.opensensor.files.resources.fm_row_groups_note
import space.opensensor.files.resources.fm_rows
import space.opensensor.files.resources.fm_rows_count
import space.opensensor.files.resources.fm_rows_next
import space.opensensor.files.resources.fm_rows_page
import space.opensensor.files.resources.fm_rows_previous
import space.opensensor.files.resources.fm_rows_range
import space.opensensor.files.resources.fm_rows_retry
import space.opensensor.files.resources.fm_rows_unavailable
import space.opensensor.files.resources.fm_sequence
import space.opensensor.files.resources.fm_size
import space.opensensor.files.resources.fm_size_footer
import space.opensensor.files.resources.fm_statistics
import space.opensensor.files.resources.fm_statistics_minmax
import space.opensensor.files.resources.fm_statistics_nulls
import space.opensensor.files.resources.fm_time
import space.opensensor.files.resources.fm_uptime_span
import space.opensensor.files.resources.fm_utc
import space.opensensor.files.resources.fm_utc_none
import space.opensensor.files.resources.fm_utc_rows
import space.opensensor.files.resources.fm_valid_rows
import space.opensensor.files.resources.fm_writer
import space.opensensor.files.resources.history_of
import space.opensensor.files.resources.row_groups
import space.opensensor.settings.DetailLevel

/**
 * Detailed and Developer view of one archived file, decoded by the app's own Parquet reader on Android
 * and iOS. Footer facts, row groups with their UTC spans (from v6 statistics), and every column's
 * decoded summary. At Developer level the rows themselves, one [RowPage] at a time from [rows], and
 * [onRowPage] asks for the page starting at a row index. Purely descriptive. Nothing here changes the archive.
 */
@Composable
fun FileMetricsScreen(state: FileMetricsState, level: DetailLevel, name: String, rows: RowsState, onRowPage: (from: Long) -> Unit, onBack: () -> Unit) {
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
            is FileMetricsState.Loaded -> Loaded(state, padding, level.developer, rows, onRowPage)
        }
    }
}

@Composable
private fun Loaded(s: FileMetricsState.Loaded, padding: PaddingValues, developer: Boolean, rows: RowsState, onRowPage: (Long) -> Unit) {
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
                idx.validRows?.let { valid -> StatLine(stringResource(Res.string.fm_valid_rows), stringResource(Res.string.history_of, valid, idx.rows)) }
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
                        c.timestampUnit?.let { stringResource(Res.string.fm_col_timestamp, it) },
                        stringResource(Res.string.fm_col_first_last, c.first, c.last).takeIf { c.first != NO_VALUE },
                        if (statsMin != null && statsMax != null) {
                            if (statsMin == c.min && statsMax == c.max) stringResource(Res.string.fm_col_footer_agrees) else stringResource(Res.string.fm_col_footer_says, statsMin, statsMax)
                        } else {
                            null
                        },
                    )
                    BodyText(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (developer && s.index.rows > 0) {
            item(key = "rows") { RowsCard(rows, onRowPage) }
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

/** One page of decoded rows, each row one line of its cells in column order, with paging by row index. */
@Composable
private fun RowsCard(state: RowsState, onPage: (Long) -> Unit) {
    LaunchedEffect(Unit) { if (state == RowsState.Idle) onPage(0) }
    SectionCard(stringResource(Res.string.fm_rows_page), modifier = Modifier.padding(top = 8.dp)) {
        val page = when (state) {
            RowsState.Idle, RowsState.Loading -> {
                CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                return@SectionCard
            }
            is RowsState.Unavailable -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.fm_rows_unavailable),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onPage(state.from) }) {
                        Icon(AppIcons.Refresh, contentDescription = stringResource(Res.string.fm_rows_retry))
                    }
                }
                return@SectionCard
            }
            is RowsState.Shown -> state.page
        }
        Text(page.columns.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        page.rows.forEachIndexed { k, cells ->
            Text(
                "#${page.from + k}  " + cells.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page.from - FileInspector.PAGE_ROWS) }, enabled = page.from > 0) {
                Icon(AppIcons.ChevronLeft, contentDescription = stringResource(Res.string.fm_rows_previous))
            }
            Text(
                stringResource(Res.string.fm_rows_range, page.from, page.to - 1, page.totalRows),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onPage(page.to) }, enabled = page.to < page.totalRows) {
                Icon(AppIcons.ChevronRight, contentDescription = stringResource(Res.string.fm_rows_next))
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, mono: Boolean = false) {
    // Wraps between words in its column instead of cutting a value off.
    BodyText(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (mono) FontFamily.Monospace else null),
    )
}

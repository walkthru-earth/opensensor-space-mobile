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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.WarningNote
import earth.walkthru.aqsync.ui.components.formatBytes
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
 * Purely descriptive — nothing here changes the archive.
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        when (val s = state) {
            FileMetricsState.Loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            is FileMetricsState.Failed -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                WarningNote(s.message)
                InfoNote("The archive copy is untouched. It passed the size, CRC-32 and PAR1 checks when it was synced. Only the app reader could not decode it.")
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
            SectionCard("File") {
                Text(s.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                StatLine("size", if (developer) "${formatBytes(s.sizeBytes)} · footer ${formatBytes(s.footerBytes.toLong())}" else formatBytes(s.sizeBytes))
                StatLine("rows", if (developer) "${idx.rows} in ${idx.rowGroups} row group${if (idx.rowGroups == 1) "" else "s"}" else idx.rows.toString())
                StatLine("columns", if (developer) "${idx.columns} · codec ${s.codec ?: NO_VALUE}" else idx.columns.toString())
                if (developer) {
                    StatLine("schema", idx.schemaVersion ?: NO_VALUE)
                    StatLine("writer", idx.createdBy ?: NO_VALUE)
                    StatLine("statistics", if (s.columnOrders > 0) "min/max per column (v6)" else "null counts only (v1 and v2)")
                    StatLine("index source", if (idx.fromStatistics) "footer statistics" else "decoded rows")
                    StatLine("decode time", "${s.decodeMillis} ms for every column")
                }
                if (!idx.isTelemetry) {
                    Spacer(Modifier.height(8.dp))
                    WarningNote("A benchmark copy of duplicated rows (purpose ${idx.purpose}). Excluded from History.")
                }
            }
        }
        item(key = "time") {
            SectionCard("Time") {
                if (idx.hasUtc) {
                    StatLine("UTC rows", "${idx.utcRows} of ${idx.rows}")
                    StatLine("first", idx.firstUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                    StatLine("last", idx.lastUtcNs?.let(::formatUtcNs) ?: NO_VALUE)
                } else {
                    StatLine("UTC", "none, the sensor had no clock")
                }
                StatLine("valid particle rows", "${idx.pmValidRows} of ${idx.rows}")
                if (developer) {
                    StatLine("uptime span", "${idx.firstMonoUs?.let(::formatMonoUs) ?: NO_VALUE} → ${idx.lastMonoUs?.let(::formatMonoUs) ?: NO_VALUE}")
                    StatLine("sequence", "${idx.firstSeq ?: NO_VALUE} to ${idx.lastSeq ?: NO_VALUE}")
                    StatLine("boot", idx.bootId?.take(12)?.plus("…") ?: NO_VALUE)
                }
            }
        }
        if (developer && s.rowGroups.size > 1) {
            item(key = "groups") {
                SectionCard("Row groups") {
                    s.rowGroups.forEach { g ->
                        StatLine(
                            "#${g.ordinal} · ${g.rows} rows · ${formatBytes(g.bytes)}",
                            if (g.firstUtcNs != null && g.lastUtcNs != null) "${formatUtcNs(g.firstUtcNs).substring(11)} – ${formatUtcNs(g.lastUtcNs).substring(11)}" else NO_VALUE,
                        )
                    }
                    InfoNote("Spans come from the footer statistics without decoding rows.")
                }
            }
        }
        item(key = "columns-header") {
            Column(Modifier.padding(top = 4.dp)) {
                Eyebrow("Columns · decoded")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Cell("name", 3f)
                    Cell("nulls", 1f)
                    Cell("min", 1.4f)
                    Cell("max", 1.4f)
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
                    val sub = buildString {
                        append(c.type.name.lowercase())
                        if (c.timestamp) append(" · timestamp ns UTC")
                        if (c.first != NO_VALUE) append(" · first ${c.first} · last ${c.last}")
                        if (c.statsMin != null && c.statsMax != null) {
                            append(if (c.statsMin == c.min && c.statsMax == c.max) " · footer agrees" else " · footer says ${c.statsMin}–${c.statsMax}")
                        }
                    }
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (developer) {
            item(key = "metadata") {
                SectionCard("File metadata", modifier = Modifier.padding(top = 8.dp)) {
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

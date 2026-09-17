package earth.walkthru.aqsync.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.FileSummary
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.ui.history.BandColumnChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Chart by file": one column per archived Parquet file of the connected station, in file order —
 * not on a time axis, so a 3-row FLUSH file is as visible as a full 15-minute window, a file on
 * estimated time is hollow, a file with no place on the timeline is marked, and a gap in the labels
 * shows where the logger was off. What the time-binned History cannot show; works from the archive
 * alone. Tap a column for the file's name, rows, span and mean.
 */
@Composable
fun FileChartCard(
    state: FileChartState?,
    onToggle: (Boolean) -> Unit,
    onMetric: (MetricSpec) -> Unit,
    onPage: (Int) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chart by file", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state == null) "One column per synced Parquet file — see every window, flush and gap." else "${state.total} files in the archive · ${state.metric.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state != null, onCheckedChange = onToggle)
            }
            if (state == null) return@Column
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metrics.chartable.forEach { m ->
                    FilterChip(selected = state.metric.id == m.id, onClick = { onMetric(m) }, label = { Text(m.label) })
                }
            }
            if (state.page.isEmpty()) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.loading) "Reading files…" else "No telemetry files in the archive for this logger yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            val columns = remember(state.page, state.metric) { state.page.mapIndexed { i, s -> s.column(i, state.metric) } }
            var selected by remember(state.page, state.metric) { mutableStateOf<HistoryColumn?>(null) }
            val fmt = remember(zone) { DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(zone) }
            // X labels: the first file of the page, then every ~quarter, by the file's first row.
            val labels = remember(state.page, zone) {
                val n = state.page.size
                val step = maxOf(1, n / 4)
                (0 until n step step).map { i -> i.toLong() to (state.page[i].firstS?.let { fmt.format(Instant.ofEpochSecond(it)) } ?: "no time") }
            }
            BandColumnChart(
                columns = columns,
                metric = state.metric,
                range = HistoryRange.DAY,
                fromS = 0,
                toS = columns.size.toLong(),
                selected = selected,
                onSelect = { selected = it },
                height = 170.dp,
                zone = zone,
                xLabels = labels,
            )
            val sel = selected?.let { c -> state.page.getOrNull(c.startS.toInt()) }
            if (sel != null) {
                SelectedFile(sel, state.metric, fmt)
            } else {
                Text(
                    "Touch a column: its file, rows, time span and mean. Faint = few valid readings (warm-up); dashed = estimated time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPage(-1) }, enabled = state.canPageBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Older files") }
                Spacer(Modifier.weight(1f))
                Text(
                    "files ${state.offset + 1}–${state.offset + state.page.size} of ${state.total}" + if (state.loading) " · reading…" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onPage(1) }, enabled = state.canPageForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Newer files") }
            }
        }
    }
}

@Composable
private fun SelectedFile(s: FileSummary, metric: MetricSpec, fmt: DateTimeFormatter) {
    val a = s.agg(metric)
    val first = s.firstS
    val last = s.lastS
    val span = if (first != null && last != null) {
        "${fmt.format(Instant.ofEpochSecond(first))} → ${fmt.format(Instant.ofEpochSecond(last))}" + if (s.timedRows == 0) " (estimated)" else ""
    } else {
        "no place on the timeline (no UTC, no later clock for this boot)"
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(s.name.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text(
            "${s.rows} rows · ${a?.n ?: 0} valid for ${metric.label} · $span",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (a != null) "mean ${fmt1(a.mean)} ${metric.unit} · min ${fmt1(a.min)} · max ${fmt1(a.max)}" else "no valid ${metric.label} readings in this file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt1(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

package space.opensensor.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toKotlinTimeZone
import space.opensensor.app.R
import space.opensensor.data.history.FileSummary
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.MetricSpec
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.metricLabel
import space.opensensor.history.BandColumnChart
import space.opensensor.ui.components.LocalUnits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Chart by file": one column per archived Parquet file of the connected thing, in file order —
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
                    val title = stringResource(R.string.chart_by_file)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        InfoButton(InfoContent(title, stringResource(R.string.info_file_chart_body)))
                    }
                    if (state != null) {
                        Text(
                            pluralStringResource(R.plurals.chart_by_file_summary, state.total, state.total, metricLabel(state.metric)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = state != null, onCheckedChange = onToggle)
            }
            if (state == null) return@Column
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.metrics.forEach { m ->
                    FilterChip(
                        selected = state.metric.id == m.id,
                        onClick = { onMetric(m) },
                        label = { Text(metricLabel(m)) },
                        shape = FloatingControl.SmallShape,
                        colors = FloatingControl.chipColors(),
                    )
                }
            }
            if (state.page.isEmpty()) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (state.loading) R.string.chart_reading_files else R.string.chart_no_files),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            val columns = remember(state.page, state.metric) { state.page.mapIndexed { i, s -> s.column(i, state.metric) } }
            var selected by remember(state.page, state.metric) { mutableStateOf<HistoryColumn?>(null) }
            val fmt = remember(zone) { DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(zone) }
            // X labels: the first file of the page, then every ~quarter, by the file's first row.
            val noTime = stringResource(R.string.chart_no_time)
            val labels = remember(state.page, zone, noTime) {
                val n = state.page.size
                val step = maxOf(1, n / 4)
                (0 until n step step).map { i -> i.toLong() to (state.page[i].firstS?.let { fmt.format(Instant.ofEpochSecond(it)) } ?: noTime) }
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
                zone = zone.toKotlinTimeZone(),
                units = LocalUnits.current,
                xLabels = labels,
                barSlot = { c -> state.page.getOrNull(c.startS.toInt())?.firstS?.let { fmt.format(Instant.ofEpochSecond(it)) } ?: noTime },
            )
            val sel = selected?.let { c -> state.page.getOrNull(c.startS.toInt()) }
            if (sel != null) {
                SelectedFile(sel, state.metric, fmt)
            } else {
                Text(
                    stringResource(R.string.chart_touch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPage(-1) }, enabled = state.canPageBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.chart_older)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.chart_page, state.offset + 1, state.offset + state.page.size, state.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onPage(1) }, enabled = state.canPageForward) { Icon(AppIcons.ArrowForward, contentDescription = stringResource(R.string.chart_newer)) }
            }
        }
    }
}

@Composable
private fun SelectedFile(s: FileSummary, metric: MetricSpec, fmt: DateTimeFormatter) {
    val a = s.agg(metric)
    val units = LocalUnits.current
    val u = metric.unit
    val first = s.firstS
    val last = s.lastS
    val span = if (first != null && last != null) {
        val range = "${fmt.format(Instant.ofEpochSecond(first))} → ${fmt.format(Instant.ofEpochSecond(last))}"
        if (s.timedRows == 0) stringResource(R.string.file_chart_estimated, range) else range
    } else {
        stringResource(R.string.file_chart_unplaced)
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(s.name.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text(
            pluralStringResource(R.plurals.file_chart_rows, a?.n ?: 0, pluralStringResource(R.plurals.fm_rows_count, s.rows.toInt(), s.rows), a?.n ?: 0, metricLabel(metric), span),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (a != null) stringResource(R.string.file_chart_stats, fmt1(units.value(a.mean, u)), units.unit(u), fmt1(units.value(a.min, u)), fmt1(units.value(a.max, u))) else stringResource(R.string.file_chart_no_valid, metricLabel(metric)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt1(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

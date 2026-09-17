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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.history.HistoryMetric
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.TimeEstimate
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.theme.pm25Band
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Day · Week · Month · Year of one logger from the phone archive. Works offline and without a
 * session: the tiers and rows come from files already verified on this phone. Every number says
 * which window it describes and how much of that window has readings.
 */
@Composable
fun HistoryScreen(
    address: String,
    onOpenToday: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDevice: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(address) { viewModel.bind(address) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = { TransportChip(state.transport, Modifier.padding(end = 12.dp)) },
            )
        },
        bottomBar = {
            DeviceBottomBar(DeviceTab.HISTORY) {
                when (it) {
                    DeviceTab.TODAY -> onOpenToday()
                    DeviceTab.FILES -> onOpenFiles()
                    DeviceTab.DEVICE -> onOpenDevice()
                    DeviceTab.HISTORY -> Unit
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "range") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HistoryRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { viewModel.setRange(r) },
                            shape = SegmentedButtonDefaults.itemShape(i, HistoryRange.entries.size),
                            label = { Text(r.label) },
                        )
                    }
                }
            }
            item(key = "span") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.page(-1) }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Earlier") }
                    Text(spanLabel(state.range, state.toS, zone), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { viewModel.page(1) }, enabled = state.canPageForward) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Later") }
                }
            }
            item(key = "chart") {
                SectionCard(state.metric.label + " · " + state.metric.unit) {
                    val s = state.series
                    when {
                        state.station == null -> Text("This logger has not been connected yet, so there is no archive to show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.isEmpty -> EmptySpan(state)
                        else -> {
                            HistoryChart(s, zone = zone)
                            Spacer(Modifier.height(8.dp))
                            Summary(s)
                        }
                    }
                }
            }
            item(key = "metric") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryMetric.entries.forEach { m ->
                        FilterChip(selected = state.metric == m, onClick = { viewModel.setMetric(m) }, label = { Text(m.label) })
                    }
                }
            }
            item(key = "archive") {
                SectionCard("Archive on this phone") {
                    StatLine("days with readings", if (state.daysWithData == 0) "none yet" else state.daysWithData.toString())
                    val firstDay = state.firstDayS
                    val lastDay = state.lastDayS
                    if (firstDay != null && lastDay != null) {
                        val f = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("UTC"))
                        StatLine("from · to (UTC days)", "${f.format(Instant.ofEpochSecond(firstDay))} · ${f.format(Instant.ofEpochSecond(lastDay))}")
                    }
                    StatLine("files with time", "${state.filesWithTime} of ${state.filesTotal}")
                    if (state.filesUnindexed > 0) StatLine("still indexing", state.filesUnindexed.toString())
                    if (state.filesEstimated > 0) StatLine("files with estimated time", state.filesEstimated.toString())
                    val untimed = state.filesTotal - state.filesWithTime - state.filesEstimated
                    if (untimed > 0) {
                        InfoNote(
                            "$untimed file${if (untimed == 1) "" else "s"} were logged while the logger had no clock and no later time set reached that power-on, " +
                                "so their rows cannot be placed on this timeline. Give the logger the time from Today so new rows get one.",
                        )
                    }
                    InfoNote("Means use only readings the sensor marked valid (warmed up, fresh, no error). Coverage is the share of the window that has such readings — 3 readings in an hour is 1 %, not an hourly average.")
                }
            }
            if (state.advancedMode) {
                item(key = "dev") {
                    SectionCard("Developer") {
                        val s = state.series
                        StatLine("source", when {
                            s == null -> "—"
                            state.range.tier != null -> "aggregate tier ${state.range.tier!!.seconds / 60} min"
                            s.binned -> "rows binned to 1 min"
                            else -> "raw 10 s rows"
                        })
                        StatLine("points", s?.points?.size?.toString() ?: "—")
                        StatLine("valid rows", s?.validRows?.toString() ?: "—")
                        StatLine("station", state.station ?: "—")
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(s: HistorySeries) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Eyebrow("average")
            Text(
                s.mean?.let { fmt(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (s.metric.banded) pm25Band(s.mean?.toDouble()) else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Eyebrow("peak")
            Text(s.max?.let { fmt(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = if (s.metric.banded) pm25Band(s.max?.toDouble()) else MaterialTheme.colorScheme.onSurface)
        }
        Column(horizontalAlignment = Alignment.End) {
            Eyebrow("coverage")
            Text("${(s.coverage * 100).toInt()} %", style = MaterialTheme.typography.headlineSmall)
            Text("${s.validRows} readings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (s.coverage < 0.5f) {
        InfoNote("Less than half of this ${s.range.label.lowercase()} has readings: the average describes the covered part only.")
    }
    if (s.hasEstimated) {
        InfoNote("Dashed stretches (${s.estimatedRows} readings) were logged before the logger had a clock; their time is estimated from a later time set on the same power-on (uncorrected drift, roughly ±${TimeEstimate.DRIFT_PPM.toInt()} s per ${(1_000_000 / TimeEstimate.DRIFT_PPM / 3600).toInt()} h of distance).")
    }
}

@Composable
private fun EmptySpan(state: HistoryUiState) {
    Column {
        Text("No readings with a timestamp in this ${state.range.label.lowercase()}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val lastDay = state.lastDayS
        if (lastDay != null && lastDay + 86_400 <= state.toS - state.range.seconds) {
            Spacer(Modifier.height(4.dp))
            Text("The latest readings are from ${DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("UTC")).format(Instant.ofEpochSecond(lastDay))}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(v: Float): String = if (v >= 100) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

private fun spanLabel(range: HistoryRange, toS: Long, zone: ZoneId): String {
    val from = Instant.ofEpochSecond(toS - range.seconds)
    val to = Instant.ofEpochSecond(toS - 1)
    return when (range) {
        HistoryRange.DAY -> {
            val d = DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone)
            val t = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
            "${d.format(from)} ${t.format(from)} → ${d.format(to)} ${t.format(to)}"
        }
        HistoryRange.WEEK, HistoryRange.MONTH -> {
            val d = DateTimeFormatter.ofPattern("d MMM").withZone(zone)
            "${d.format(from)} → ${d.format(to)}"
        }
        HistoryRange.YEAR -> {
            val d = DateTimeFormatter.ofPattern("MMM yyyy").withZone(zone)
            "${d.format(from)} → ${d.format(to)}"
        }
    }
}

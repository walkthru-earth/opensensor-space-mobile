package earth.walkthru.aqsync.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.Columns
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.TimeEstimate
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.theme.color
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
                ChartCard(state, zone, onOpenDay = { dayStartS -> viewModel.openDay(dayStartS) })
            }
            item(key = "metric") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metrics.chartable.forEach { m ->
                        FilterChip(selected = state.metric.id == m.id, onClick = { viewModel.setMetric(m) }, label = { Text(m.label) })
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
                        StatLine(
                            "source",
                            when {
                                s == null -> "—"
                                state.range.tier != null -> "aggregate tier ${state.range.tier!!.seconds / 60} min"
                                s.binned -> "rows binned to 1 min"
                                else -> "raw 10 s rows"
                            }
                        )
                        StatLine("points", s?.points?.size?.toString() ?: "—")
                        StatLine("valid rows", s?.validRows?.toString() ?: "—")
                        StatLine("station", state.station ?: "—")
                    }
                }
            }
        }
    }
}

/**
 * The picture depends on the span: a day is a ring plus hourly columns, a week hourly columns,
 * a month or a year a calendar of days plus daily / weekly columns. Every column is coloured by its
 * own band; touch one to read it.
 */
@Composable
private fun ChartCard(state: HistoryUiState, zone: ZoneId, onOpenDay: (Long) -> Unit) {
    val s = state.series
    var selected by remember(state.range, state.toS, state.metric) { mutableStateOf<HistoryColumn?>(null) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(state.metric.label)
                Spacer(Modifier.width(6.dp))
                Eyebrow(state.metric.unit, uppercase = false)
                Spacer(Modifier.weight(1f))
                if (s != null && !s.isEmpty) Eyebrow("${(s.coverage * 100).toInt()} % covered", uppercase = false)
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.station == null -> Text("This logger has not been connected yet, so there is no archive to show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                s == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.isEmpty -> EmptySpan(state)
                else -> {
                    val slot = Columns.slotSeconds(state.range)
                    val align = if (state.range == HistoryRange.YEAR) MONDAY_EPOCH_S else 0L
                    val columns = remember(s) { Columns.rebin(s, slot, align) }
                    when (state.range) {
                        HistoryRange.DAY -> {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                DayRing(
                                    columns = columns,
                                    metric = state.metric,
                                    dayStartS = s.fromS,
                                    mean = s.mean,
                                    coverage = s.coverage,
                                    nowS = System.currentTimeMillis() / 1000,
                                    selected = selected,
                                    zone = zone,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        HistoryRange.MONTH, HistoryRange.YEAR -> {
                            val days = remember(s) { Columns.rebin(s, 86_400L, 0L) }
                            CalendarHeatmap(days, state.metric, s.fromS, s.toS, onPickDay = { onOpenDay(it.startS) })
                            InfoNote("One cell per UTC day, coloured by its average; tap a day to open it.")
                            Spacer(Modifier.height(8.dp))
                        }
                        HistoryRange.WEEK -> Unit
                    }
                    BandColumnChart(
                        columns = columns,
                        metric = state.metric,
                        range = state.range,
                        fromS = s.fromS,
                        toS = s.toS,
                        selected = selected,
                        onSelect = { selected = it },
                        height = if (state.range == HistoryRange.DAY) 170.dp else 200.dp,
                        zone = zone,
                    )
                    SelectedColumn(selected, state, zone)
                    Spacer(Modifier.height(8.dp))
                    Summary(s)
                }
            }
        }
    }
}

/** What the touched column says — time slot, value with its mood, peak, coverage, estimated or not. */
@Composable
private fun SelectedColumn(c: HistoryColumn?, state: HistoryUiState, zone: ZoneId) {
    val text = if (c == null) {
        "Touch a column to read it"
    } else {
        val fmt = when (state.range) {
            HistoryRange.DAY -> DateTimeFormatter.ofPattern("HH:mm")
            HistoryRange.WEEK -> DateTimeFormatter.ofPattern("EEE HH:mm")
            HistoryRange.MONTH -> DateTimeFormatter.ofPattern("EEE d MMM")
            HistoryRange.YEAR -> DateTimeFormatter.ofPattern("d MMM")
        }.withZone(zone)
        val slot = when (state.range) {
            HistoryRange.MONTH -> fmt.format(Instant.ofEpochSecond(c.startS))
            HistoryRange.YEAR -> "week of ${fmt.format(Instant.ofEpochSecond(c.startS))}"
            else -> "${fmt.format(Instant.ofEpochSecond(c.startS))}–${DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(c.endS))}"
        }
        val m = c.mean
        if (m == null) {
            "$slot · no readings"
        } else {
            buildString {
                append(slot).append(" · ").append(fmt(m)).append(' ').append(state.metric.unit)
                state.metric.moodOf(m.toDouble())?.let { append(" · ").append(it.title) }
                c.max?.takeIf { it > m * 1.15f }?.let { append(" · peak ").append(fmt(it)) }
                append(" · ").append((c.coverage * 100).toInt()).append(" % covered")
                if (c.estimated) append(" · estimated time")
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (c == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
}

/** 1970-01-05 was a Monday: week columns align to it. */
private const val MONDAY_EPOCH_S = 4 * 86_400L

@Composable
private fun Summary(s: HistorySeries) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Eyebrow("average")
            Text(
                s.mean?.let { fmt(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = s.mean?.let { s.metric.moodOf(it.toDouble())?.color() } ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Eyebrow("peak")
            Text(s.max?.let { fmt(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, color = s.max?.let { s.metric.moodOf(it.toDouble())?.color() } ?: MaterialTheme.colorScheme.onSurface)
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
        HistoryRange.DAY -> DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(zone).format(from)
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

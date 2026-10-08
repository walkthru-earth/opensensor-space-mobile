package earth.walkthru.aqsync.ui.history

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.Columns
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.ui.components.DeviceSectionTabs
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.Eyebrow
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.aqsync.ui.components.NO_VALUE
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.titleText
import earth.walkthru.aqsync.ui.theme.color
import earth.walkthru.aqsync.ui.theme.textColorOnSurface
import kotlinx.datetime.toJavaZoneId
import kotlinx.datetime.toKotlinTimeZone
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
    viewModel: HistoryViewModel = hiltViewModel<HistoryViewModel, HistoryViewModel.Factory>(key = address, creationCallback = { it.create(address) }),
) {
    LaunchedEffect(address) { viewModel.bind(address) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = state.zone

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.history_title)) },
                    actions = { TransportChip(state.transport, Modifier.padding(end = 12.dp)) },
                )
                DeviceSectionTabs(DeviceTab.HISTORY, containerColor = MaterialTheme.colorScheme.surface) {
                    when (it) {
                        DeviceTab.TODAY -> onOpenToday()
                        DeviceTab.FILES -> onOpenFiles()
                        DeviceTab.DEVICE -> onOpenDevice()
                        DeviceTab.HISTORY -> Unit
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.initializing) {
                item(key = "loading") { InfoNote(stringResource(R.string.history_loading)) }
                return@LazyColumn
            }
            if (state.stations.size > 1) {
                item(key = "station-epochs") {
                    Column {
                        Text(stringResource(R.string.history_from), style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.stations.forEach { station ->
                                val current = station == state.stations.last()
                                val base = stringResource(if (current) R.string.history_current else R.string.history_earlier)
                                val label = if (state.advancedMode) "$base · ${station.take(8)}" else base
                                val chipDescription = stringResource(R.string.history_station_chip, label, station)
                                FilterChip(
                                    selected = state.station == station,
                                    onClick = { viewModel.selectStation(station) },
                                    label = { Text(label) },
                                    modifier = Modifier.semantics { contentDescription = chipDescription },
                                )
                            }
                        }
                    }
                }
            }
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
                    IconButton(onClick = { viewModel.page(-1) }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.history_page_earlier)) }
                    Text(spanLabel(state.range, state.window), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { viewModel.page(1) }, enabled = state.canPageForward) { Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.history_page_later)) }
                }
            }
            item(key = "chart") {
                if (state.availableMetrics.isEmpty()) {
                    InfoNote(stringResource(if (state.filesUntimed > 0) R.string.history_untimed else R.string.history_nothing_indexed))
                } else {
                    ChartCard(state, zone, onOpenDay = { dayStartS -> viewModel.openDay(dayStartS) })
                }
            }
            item(key = "metric") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableMetrics.forEach { m ->
                        FilterChip(selected = state.metric.id == m.id, onClick = { viewModel.setMetric(m) }, label = { Text(m.label) })
                    }
                }
            }
            item(key = "archive") {
                val archiveTitle = stringResource(R.string.history_archive)
                SectionCard(
                    archiveTitle,
                    trailing = {
                        InfoButton(
                            InfoContent(
                                archiveTitle,
                                stringResource(R.string.info_history_body1),
                                stringResource(R.string.info_history_body2),
                                stringResource(R.string.info_history_body3),
                                stringResource(R.string.info_history_body4),
                            ),
                        )
                    },
                ) {
                    StatLine(stringResource(R.string.history_days), if (state.daysWithData == 0) stringResource(R.string.history_none_yet) else state.daysWithData.toString())
                    val firstDay = state.firstDayS
                    val lastDay = state.lastDayS
                    if (firstDay != null && lastDay != null) {
                        val f = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("UTC"))
                        if (state.advancedMode) {
                            StatLine(stringResource(R.string.history_span_utc), "${f.format(Instant.ofEpochSecond(firstDay))} · ${f.format(Instant.ofEpochSecond(lastDay))}")
                        }
                    }
                    StatLine(stringResource(R.string.history_files_time), stringResource(R.string.history_of, state.filesWithTime, state.filesTotal))
                    if (state.filesPendingIndex > 0) StatLine(stringResource(R.string.history_waiting_index), state.filesPendingIndex.toString())
                    if (state.filesIndexFailed > 0) StatLine(stringResource(R.string.history_index_failed), state.filesIndexFailed.toString())
                    if (state.filesEstimated > 0) StatLine(stringResource(R.string.history_estimated), state.filesEstimated.toString())
                    if (state.filesOtherStation > 0) InfoNote(pluralStringResource(R.plurals.history_other_station, state.filesOtherStation, state.filesOtherStation))
                    if (state.filesUntimed > 0) InfoNote(pluralStringResource(R.plurals.history_untimed_files, state.filesUntimed, state.filesUntimed))
                }
            }
            if (state.advancedMode) {
                item(key = "dev") {
                    SectionCard(stringResource(R.string.history_dev_title)) {
                        val s = state.series
                        StatLine(
                            stringResource(R.string.history_dev_source),
                            when {
                                s == null -> NO_VALUE
                                state.range.tier != null -> stringResource(R.string.history_dev_tier, state.range.tier!!.seconds / 60)
                                s.binned -> stringResource(R.string.history_dev_binned)
                                else -> stringResource(R.string.history_dev_raw)
                            }
                        )
                        StatLine(stringResource(R.string.history_dev_points), s?.points?.size?.toString() ?: NO_VALUE)
                        StatLine(stringResource(R.string.history_dev_valid_rows), s?.validRows?.toString() ?: NO_VALUE)
                        StatLine(stringResource(R.string.history_dev_station), state.station ?: NO_VALUE)
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
                Eyebrow(LocalUnits.current.unit(state.metric.unit), uppercase = false)
                Spacer(Modifier.weight(1f))
                if (s != null && !s.isEmpty) Eyebrow(stringResource(R.string.history_covered, (s.coverage * 100).toInt()), uppercase = false)
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.station == null -> Text(stringResource(R.string.history_never_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                s == null -> Text(stringResource(R.string.history_loading_short), color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.isEmpty -> EmptySpan(state)
                else -> {
                    val columns = remember(s, zone) {
                        when (state.range) {
                            HistoryRange.DAY, HistoryRange.WEEK -> Columns.rebinHours(s)
                            HistoryRange.MONTH -> Columns.rebinLocalDays(s, zone.toKotlinTimeZone())
                            HistoryRange.YEAR -> Columns.rebinLocalWeeks(s, zone.toKotlinTimeZone())
                        }
                    }
                    when (state.range) {
                        HistoryRange.DAY -> {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                DayRing(
                                    columns = columns,
                                    metric = state.metric,
                                    dayStartS = s.fromS,
                                    dayEndS = s.toS,
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
                            val days = remember(s, zone) { Columns.rebinLocalDays(s, zone.toKotlinTimeZone()) }
                            CalendarHeatmap(days, state.metric, s.fromS, s.toS, onPickDay = { onOpenDay(it.startS) }, zone = zone)
                            InfoNote(stringResource(R.string.history_tap_day))
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

/** What the touched column says. Time slot, value with its mood, peak, coverage, estimated or not. */
@Composable
private fun SelectedColumn(c: HistoryColumn?, state: HistoryUiState, zone: ZoneId) {
    val text = if (c == null) {
        stringResource(R.string.chart_touch)
    } else {
        val fmt = when (state.range) {
            HistoryRange.DAY -> DateTimeFormatter.ofPattern("HH:mm XXX")
            HistoryRange.WEEK -> DateTimeFormatter.ofPattern("EEE HH:mm XXX")
            HistoryRange.MONTH -> DateTimeFormatter.ofPattern("EEE d MMM")
            HistoryRange.YEAR -> DateTimeFormatter.ofPattern("d MMM")
        }.withZone(zone)
        val slot = when (state.range) {
            HistoryRange.MONTH -> fmt.format(Instant.ofEpochSecond(c.startS))
            HistoryRange.YEAR -> stringResource(R.string.history_week_of, fmt.format(Instant.ofEpochSecond(c.startS)))
            else -> "${fmt.format(Instant.ofEpochSecond(c.startS))} → ${DateTimeFormatter.ofPattern("HH:mm XXX").withZone(zone).format(Instant.ofEpochSecond(c.endS))}"
        }
        val m = c.mean
        val units = LocalUnits.current
        val unit = state.metric.unit
        if (m == null) {
            "$slot · " + stringResource(R.string.history_no_readings_short)
        } else {
            listOfNotNull(
                slot,
                fmt(units.value(m, unit)) + " " + units.unit(unit),
                state.metric.moodOf(m.toDouble())?.titleText(),
                c.max?.takeIf { it > m * 1.15f }?.let { stringResource(R.string.history_peak_value, fmt(units.value(it, unit))) },
                stringResource(R.string.history_covered, (c.coverage * 100).toInt()),
                stringResource(R.string.history_estimated_time).takeIf { c.estimated },
            ).joinToString(" · ")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (c == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
}

@Composable
private fun Summary(s: HistorySeries) {
    // Moods and colours read the SI value. Only the printed number converts.
    val units = LocalUnits.current
    val unit = s.metric.unit
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Eyebrow(stringResource(R.string.history_average))
            Text(
                s.mean?.let { fmt(units.value(it, unit)) } ?: NO_VALUE,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = s.mean?.let { s.metric.moodOf(it.toDouble())?.textColorOnSurface() } ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Eyebrow(stringResource(R.string.history_peak))
            Text(s.max?.let { fmt(units.value(it, unit)) } ?: NO_VALUE, style = MaterialTheme.typography.headlineSmall, color = s.max?.let { s.metric.moodOf(it.toDouble())?.textColorOnSurface() } ?: MaterialTheme.colorScheme.onSurface)
        }
        Column(horizontalAlignment = Alignment.End) {
            Eyebrow(stringResource(R.string.history_coverage))
            Text("${(s.coverage * 100).toInt()} %", style = MaterialTheme.typography.headlineSmall)
            Text(pluralStringResource(R.plurals.history_readings, s.validRows.toInt(), s.validRows), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (s.coverage < 0.5f) {
        InfoNote(stringResource(R.string.history_low_coverage))
    }
}

@Composable
private fun EmptySpan(state: HistoryUiState) {
    Column {
        Text(stringResource(R.string.history_no_readings), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val lastDay = state.lastDayS
        if (lastDay != null && Instant.ofEpochSecond(lastDay).atZone(state.zone).toLocalDate().isBefore(Instant.ofEpochSecond(state.window.fromS).atZone(state.zone).toLocalDate())) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.history_latest, DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("UTC")).format(Instant.ofEpochSecond(lastDay))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(v: Float): String = if (v >= 100) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

private fun spanLabel(range: HistoryRange, window: HistoryWindow): String {
    val from = Instant.ofEpochSecond(window.fromS)
    val to = Instant.ofEpochSecond(window.toS - 1)
    val zone = window.zone.toJavaZoneId()
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

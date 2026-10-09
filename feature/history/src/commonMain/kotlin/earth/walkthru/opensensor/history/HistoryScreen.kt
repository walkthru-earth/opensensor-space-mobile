package earth.walkthru.opensensor.history

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.Columns
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.Eyebrow
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoButton
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.InfoNote
import earth.walkthru.opensensor.designsystem.PeacockExpression
import earth.walkthru.opensensor.designsystem.PeacockIcon
import earth.walkthru.opensensor.designsystem.SectionCard
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.history.resources.Res
import earth.walkthru.opensensor.history.resources.chart_touch
import earth.walkthru.opensensor.history.resources.history_archive
import earth.walkthru.opensensor.history.resources.history_average
import earth.walkthru.opensensor.history.resources.history_back
import earth.walkthru.opensensor.history.resources.history_coverage
import earth.walkthru.opensensor.history.resources.history_covered
import earth.walkthru.opensensor.history.resources.history_current
import earth.walkthru.opensensor.history.resources.history_days
import earth.walkthru.opensensor.history.resources.history_dev_binned
import earth.walkthru.opensensor.history.resources.history_dev_points
import earth.walkthru.opensensor.history.resources.history_dev_raw
import earth.walkthru.opensensor.history.resources.history_dev_source
import earth.walkthru.opensensor.history.resources.history_dev_station
import earth.walkthru.opensensor.history.resources.history_dev_tier
import earth.walkthru.opensensor.history.resources.history_dev_title
import earth.walkthru.opensensor.history.resources.history_dev_valid_rows
import earth.walkthru.opensensor.history.resources.history_earlier
import earth.walkthru.opensensor.history.resources.history_estimated
import earth.walkthru.opensensor.history.resources.history_estimated_time
import earth.walkthru.opensensor.history.resources.history_files_time
import earth.walkthru.opensensor.history.resources.history_from
import earth.walkthru.opensensor.history.resources.history_index_failed
import earth.walkthru.opensensor.history.resources.history_latest
import earth.walkthru.opensensor.history.resources.history_loading
import earth.walkthru.opensensor.history.resources.history_loading_short
import earth.walkthru.opensensor.history.resources.history_low_coverage
import earth.walkthru.opensensor.history.resources.history_never_connected
import earth.walkthru.opensensor.history.resources.history_no_readings
import earth.walkthru.opensensor.history.resources.history_no_readings_short
import earth.walkthru.opensensor.history.resources.history_no_value
import earth.walkthru.opensensor.history.resources.history_none_yet
import earth.walkthru.opensensor.history.resources.history_nothing_indexed
import earth.walkthru.opensensor.history.resources.history_of
import earth.walkthru.opensensor.history.resources.history_other_station
import earth.walkthru.opensensor.history.resources.history_page_earlier
import earth.walkthru.opensensor.history.resources.history_page_later
import earth.walkthru.opensensor.history.resources.history_peak
import earth.walkthru.opensensor.history.resources.history_peak_value
import earth.walkthru.opensensor.history.resources.history_range_day
import earth.walkthru.opensensor.history.resources.history_range_month
import earth.walkthru.opensensor.history.resources.history_range_week
import earth.walkthru.opensensor.history.resources.history_range_year
import earth.walkthru.opensensor.history.resources.history_readings
import earth.walkthru.opensensor.history.resources.history_span_utc
import earth.walkthru.opensensor.history.resources.history_station_chip
import earth.walkthru.opensensor.history.resources.history_tap_day
import earth.walkthru.opensensor.history.resources.history_title
import earth.walkthru.opensensor.history.resources.history_untimed
import earth.walkthru.opensensor.history.resources.history_untimed_files
import earth.walkthru.opensensor.history.resources.history_waiting_index
import earth.walkthru.opensensor.history.resources.history_week_of
import earth.walkthru.opensensor.history.resources.info_history_body1
import earth.walkthru.opensensor.history.resources.info_history_body2
import earth.walkthru.opensensor.history.resources.info_history_body3
import earth.walkthru.opensensor.history.resources.info_history_body4
import earth.walkthru.opensensor.history.resources.info_history_body5
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Day, Week, Month and Year of one station from the phone archive, the same screen on Android and
 * iOS. It works offline and without a session, because the tiers and rows come from files already
 * verified on this phone. Every number says which window it describes and how much of that window
 * has readings. [topBar] is the platform header (Android, the device tabs, iOS, [HistoryTopBar]).
 */
@Composable
fun HistoryScreen(
    controller: HistoryController,
    units: UnitFormatter,
    level: DetailLevel,
    topBar: @Composable () -> Unit,
) {
    val state by controller.state.collectAsState()
    val noValue = stringResource(Res.string.history_no_value)
    Scaffold(topBar = topBar) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.initializing) {
                item(key = "loading") { HistoryStateMessage(stringResource(Res.string.history_loading), PeacockExpression.WORKING) }
                return@LazyColumn
            }
            if (state.stations.size > 1) {
                item(key = "station-epochs") { StationChips(state, level, controller::selectStation) }
            }
            item(key = "range") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    HistoryRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { controller.setRange(r) },
                            shape = FloatingControl.segmentShape(i, HistoryRange.entries.size),
                            colors = FloatingControl.segmentedColors(),
                            label = { Text(rangeLabel(r)) },
                        )
                    }
                }
            }
            item(key = "span") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.page(-1) }) { Icon(AppIcons.ChevronLeft, contentDescription = stringResource(Res.string.history_page_earlier)) }
                    Text(spanLabel(state.range, state.window), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    IconButton(onClick = { controller.page(1) }, enabled = state.canPageForward) { Icon(AppIcons.ChevronRight, contentDescription = stringResource(Res.string.history_page_later)) }
                }
            }
            item(key = "chart") {
                if (state.availableMetrics.isEmpty()) {
                    HistoryStateMessage(stringResource(if (state.files.untimed > 0) Res.string.history_untimed else Res.string.history_nothing_indexed))
                } else {
                    ChartCard(state, units, controller.nowS(), onOpenDay = controller::openDay)
                }
            }
            item(key = "metric") {
                // One choice of many, so screen readers hear the chips as a group.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableMetrics.forEach { m ->
                        FilterChip(
                            selected = state.metric.id == m.id,
                            onClick = { controller.setMetric(m) },
                            label = { Text(m.label) },
                            shape = FloatingControl.SmallShape,
                            colors = FloatingControl.chipColors(),
                        )
                    }
                }
            }
            item(key = "archive") { ArchiveCard(state, level) }
            if (level.developer) {
                item(key = "dev") {
                    SectionCard(stringResource(Res.string.history_dev_title)) {
                        val s = state.series
                        val tier = state.range.tier
                        StatLine(
                            stringResource(Res.string.history_dev_source),
                            when {
                                s == null -> noValue
                                tier != null -> stringResource(Res.string.history_dev_tier, (tier.seconds / 60).toInt())
                                s.binned -> stringResource(Res.string.history_dev_binned)
                                else -> stringResource(Res.string.history_dev_raw)
                            },
                        )
                        StatLine(stringResource(Res.string.history_dev_points), s?.points?.size?.toString() ?: noValue)
                        StatLine(stringResource(Res.string.history_dev_valid_rows), s?.validRows?.toString() ?: noValue)
                        StatLine(stringResource(Res.string.history_dev_station), state.station ?: noValue)
                    }
                }
            }
        }
    }
}

/** The iOS header: a back arrow and the title. Android keeps its own app bar and device tabs. */
@Composable
fun HistoryTopBar(onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(stringResource(Res.string.history_title), Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.history_back)) }
            }
        },
    )
}

/** The screen title, for a platform header that hosts [HistoryScreen]. */
@Composable
fun historyTitle(): String = stringResource(Res.string.history_title)

/**
 * Station generations as chips. The newest is "Current", older ones "Earlier", and Developer adds
 * the first characters of the station id. The spoken label names the full station.
 */
@Composable
private fun StationChips(state: HistoryUiState, level: DetailLevel, onSelect: (String) -> Unit) {
    Column {
        Text(stringResource(Res.string.history_from), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.stations.forEach { station ->
                val current = station == state.stations.last()
                val base = stringResource(if (current) Res.string.history_current else Res.string.history_earlier)
                val label = if (level.developer) "$base · ${station.take(8)}" else base
                val chipDescription = stringResource(Res.string.history_station_chip, label, station)
                FilterChip(
                    selected = state.station == station,
                    onClick = { onSelect(station) },
                    label = { Text(label) },
                    shape = FloatingControl.SmallShape,
                    colors = FloatingControl.chipColors(),
                    modifier = Modifier.semantics { contentDescription = chipDescription },
                )
            }
        }
    }
}

@Composable
private fun ArchiveCard(state: HistoryUiState, level: DetailLevel) {
    val archiveTitle = stringResource(Res.string.history_archive)
    val files = state.files
    SectionCard(
        archiveTitle,
        trailing = {
            InfoButton(
                InfoContent(
                    archiveTitle,
                    stringResource(Res.string.info_history_body1),
                    stringResource(Res.string.info_history_body2),
                    stringResource(Res.string.info_history_body3),
                    stringResource(Res.string.info_history_body4),
                    stringResource(Res.string.info_history_body5),
                ),
            )
        },
    ) {
        StatLine(stringResource(Res.string.history_days), if (state.days.count == 0) stringResource(Res.string.history_none_yet) else state.days.count.toString())
        val firstDay = state.days.firstDayS
        val lastDay = state.days.lastDayS
        if (firstDay != null && lastDay != null && level.developer) {
            StatLine(stringResource(Res.string.history_span_utc), formatEpoch(firstDay, TimeZone.UTC, UTC_DAY) + " · " + formatEpoch(lastDay, TimeZone.UTC, UTC_DAY))
        }
        StatLine(stringResource(Res.string.history_files_time), stringResource(Res.string.history_of, files.withTime, files.total))
        if (files.pending > 0) StatLine(stringResource(Res.string.history_waiting_index), files.pending.toString())
        if (files.failed > 0) StatLine(stringResource(Res.string.history_index_failed), files.failed.toString())
        if (files.estimated > 0) StatLine(stringResource(Res.string.history_estimated), files.estimated.toString())
        if (files.otherStation > 0) InfoNote(pluralStringResource(Res.plurals.history_other_station, files.otherStation, files.otherStation))
        if (files.untimed > 0) InfoNote(pluralStringResource(Res.plurals.history_untimed_files, files.untimed, files.untimed))
    }
}

/**
 * The picture depends on the span: a day is a ring plus hourly columns, a week hourly columns, a
 * month or a year a calendar of days plus daily or weekly columns. Touch a column to read it.
 */
@Composable
private fun ChartCard(state: HistoryUiState, units: UnitFormatter, nowS: Long, onOpenDay: (Long) -> Unit) {
    val s = state.series
    val zone = state.zone
    var selected by remember(state.range, state.toS, state.metric) { mutableStateOf<HistoryColumn?>(null) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(state.metric.label, heading = true)
                Spacer(Modifier.width(6.dp))
                Eyebrow(units.unit(state.metric.unit), uppercase = false)
                Spacer(Modifier.weight(1f))
                if (s != null && !s.isEmpty) Eyebrow(stringResource(Res.string.history_covered, (s.coverage * 100).toInt()), uppercase = false)
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.station == null -> HistoryStateMessage(stringResource(Res.string.history_never_connected))
                s == null -> HistoryStateMessage(stringResource(Res.string.history_loading_short), PeacockExpression.WORKING)
                s.isEmpty -> EmptySpan(state)
                else -> {
                    val columns = remember(s, zone) {
                        when (state.range) {
                            HistoryRange.DAY, HistoryRange.WEEK -> Columns.rebinHours(s)
                            HistoryRange.MONTH -> Columns.rebinLocalDays(s, zone)
                            HistoryRange.YEAR -> Columns.rebinLocalWeeks(s, zone)
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
                                    nowS = nowS,
                                    selected = selected,
                                    zone = zone,
                                    units = units,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        HistoryRange.MONTH, HistoryRange.YEAR -> {
                            val days = remember(s, zone) { Columns.rebinLocalDays(s, zone) }
                            CalendarHeatmap(days, state.metric, s.fromS, s.toS, onPickDay = { onOpenDay(it.startS) }, zone = zone, units = units)
                            InfoNote(stringResource(Res.string.history_tap_day))
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
                        units = units,
                    )
                    Text(
                        averagingNote(state.range, state.metric),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SelectedColumn(selected, state, units)
                    Spacer(Modifier.height(8.dp))
                    Summary(s, units)
                }
            }
        }
    }
}

/** What the touched column says: slot, value with its mood and WHO step in words, peak, coverage, estimated or not. */
@Composable
private fun SelectedColumn(c: HistoryColumn?, state: HistoryUiState, units: UnitFormatter) {
    val zone = state.zone
    val text = if (c == null) {
        stringResource(Res.string.chart_touch)
    } else {
        val pattern = when (state.range) {
            HistoryRange.DAY -> "HH:mm XXX"
            HistoryRange.WEEK -> "EEE HH:mm XXX"
            HistoryRange.MONTH -> "EEE d MMM"
            HistoryRange.YEAR -> "d MMM"
        }
        val start = formatEpoch(c.startS, zone, pattern)
        val slot = when (state.range) {
            HistoryRange.MONTH -> start
            HistoryRange.YEAR -> stringResource(Res.string.history_week_of, start)
            else -> start + " → " + formatEpoch(c.endS, zone, "HH:mm XXX")
        }
        val m = c.mean
        val unit = state.metric.unit
        if (m == null) {
            slot + " · " + stringResource(Res.string.history_no_readings_short)
        } else {
            val mood = state.metric.moodOf(m.toDouble())
            listOfNotNull(
                slot,
                fmtValue(units.value(m, unit)) + " " + units.unit(unit),
                mood?.let { it.titleText() + " · " + it.step.labelText() },
                c.max?.takeIf { it > m * 1.15f }?.let { stringResource(Res.string.history_peak_value, fmtValue(units.value(it, unit))) },
                stringResource(Res.string.history_covered, (c.coverage * 100).toInt()),
                if (c.estimated) stringResource(Res.string.history_estimated_time) else null,
            ).joinToString(" · ")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (c == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
}

@Composable
private fun Summary(s: HistorySeries, units: UnitFormatter) {
    // Moods and colours read the SI value. Only the printed number converts.
    val unit = s.metric.unit
    val noValue = stringResource(Res.string.history_no_value)
    // The value colours carry the mood, so each figure also states it in words.
    val meanMood = s.mean?.let { s.metric.moodOf(it.toDouble()) }
    val peakMood = s.max?.let { s.metric.moodOf(it.toDouble()) }
    val meanState = meanMood?.let { it.titleText() + ", " + it.step.labelText() }
    val peakState = peakMood?.let { it.titleText() + ", " + it.step.labelText() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.semantics(mergeDescendants = true) { meanState?.let { stateDescription = it } }) {
            Eyebrow(stringResource(Res.string.history_average))
            Text(
                s.mean?.let { fmtValue(units.value(it, unit)) } ?: noValue,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = meanMood?.textColorOnSurface() ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.semantics(mergeDescendants = true) { peakState?.let { stateDescription = it } }) {
            Eyebrow(stringResource(Res.string.history_peak))
            Text(
                s.max?.let { fmtValue(units.value(it, unit)) } ?: noValue,
                style = MaterialTheme.typography.headlineSmall,
                color = peakMood?.textColorOnSurface() ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
            Eyebrow(stringResource(Res.string.history_coverage))
            Text("${(s.coverage * 100).toInt()} %", style = MaterialTheme.typography.headlineSmall)
            Text(pluralStringResource(Res.plurals.history_readings, s.validRows.toInt(), s.validRows), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (s.coverage < 0.5f) InfoNote(stringResource(Res.string.history_low_coverage))
}

@Composable
private fun EmptySpan(state: HistoryUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PeacockIcon(PeacockExpression.CONFUSED, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.history_no_readings), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val lastDay = state.days.lastDayS
            if (lastDay != null && localDateOf(lastDay, state.zone) < localDateOf(state.window.fromS, state.zone)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.history_latest, formatEpoch(lastDay, TimeZone.UTC, UTC_DAY)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryStateMessage(text: String, expression: PeacockExpression = PeacockExpression.CONFUSED) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PeacockIcon(expression, Modifier.size(48.dp))
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun rangeLabel(range: HistoryRange): String = stringResource(
    when (range) {
        HistoryRange.DAY -> Res.string.history_range_day
        HistoryRange.WEEK -> Res.string.history_range_week
        HistoryRange.MONTH -> Res.string.history_range_month
        HistoryRange.YEAR -> Res.string.history_range_year
    },
)

/** The visible span as a heading, its last day inclusive. */
internal fun spanLabel(range: HistoryRange, window: HistoryWindow): String {
    val zone = window.zone
    val from = window.fromS
    val to = window.toS - 1
    return when (range) {
        HistoryRange.DAY -> formatEpoch(from, zone, "EEEE d MMMM")
        HistoryRange.WEEK, HistoryRange.MONTH -> formatEpoch(from, zone, "d MMM") + " → " + formatEpoch(to, zone, "d MMM")
        HistoryRange.YEAR -> formatEpoch(from, zone, "MMM yyyy") + " → " + formatEpoch(to, zone, "MMM yyyy")
    }
}

private const val UTC_DAY = "d MMM yyyy"

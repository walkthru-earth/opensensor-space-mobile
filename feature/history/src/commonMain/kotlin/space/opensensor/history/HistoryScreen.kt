package space.opensensor.history

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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.history.Columns
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.localDateOf
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.PeacockIcon
import space.opensensor.designsystem.SectionCard
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.metricLabel
import space.opensensor.designsystem.pm25CorrectionInfo
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.chart_touch
import space.opensensor.history.resources.history_archive
import space.opensensor.history.resources.history_average
import space.opensensor.history.resources.history_back
import space.opensensor.history.resources.history_coverage
import space.opensensor.history.resources.history_covered
import space.opensensor.history.resources.history_current
import space.opensensor.history.resources.history_days
import space.opensensor.history.resources.history_dev_binned
import space.opensensor.history.resources.history_dev_points
import space.opensensor.history.resources.history_dev_raw
import space.opensensor.history.resources.history_dev_source
import space.opensensor.history.resources.history_dev_thing
import space.opensensor.history.resources.history_dev_tier
import space.opensensor.history.resources.history_dev_title
import space.opensensor.history.resources.history_dev_valid_rows
import space.opensensor.history.resources.history_earlier
import space.opensensor.history.resources.history_estimated
import space.opensensor.history.resources.history_estimated_time
import space.opensensor.history.resources.history_files_time
import space.opensensor.history.resources.history_from
import space.opensensor.history.resources.history_index_failed
import space.opensensor.history.resources.history_latest
import space.opensensor.history.resources.history_loading
import space.opensensor.history.resources.history_loading_short
import space.opensensor.history.resources.history_low_coverage
import space.opensensor.history.resources.history_never_connected
import space.opensensor.history.resources.history_no_readings
import space.opensensor.history.resources.history_no_readings_short
import space.opensensor.history.resources.history_no_value
import space.opensensor.history.resources.history_none_yet
import space.opensensor.history.resources.history_nothing_indexed
import space.opensensor.history.resources.history_of
import space.opensensor.history.resources.history_other_thing
import space.opensensor.history.resources.history_page_earlier
import space.opensensor.history.resources.history_page_later
import space.opensensor.history.resources.history_peak
import space.opensensor.history.resources.history_peak_value
import space.opensensor.history.resources.history_range_day
import space.opensensor.history.resources.history_range_month
import space.opensensor.history.resources.history_range_week
import space.opensensor.history.resources.history_range_year
import space.opensensor.history.resources.history_read_failed
import space.opensensor.history.resources.history_readings
import space.opensensor.history.resources.history_span_utc
import space.opensensor.history.resources.history_tap_day
import space.opensensor.history.resources.history_thing_chip
import space.opensensor.history.resources.history_title
import space.opensensor.history.resources.history_untimed
import space.opensensor.history.resources.history_untimed_files
import space.opensensor.history.resources.history_waiting_index
import space.opensensor.history.resources.history_week_of
import space.opensensor.history.resources.info_history_body1
import space.opensensor.history.resources.info_history_body2
import space.opensensor.history.resources.info_history_body3
import space.opensensor.history.resources.info_history_body4
import space.opensensor.history.resources.info_history_body5
import space.opensensor.history.resources.info_history_body5_simple
import space.opensensor.settings.DetailLevel
import space.opensensor.units.UnitFormatter

/**
 * Day, Week, Month and Year of one thing from the phone archive, the same screen on Android and
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
    val pickable = pickerMetrics(state.availableMetrics, level)
    // Simple shows corrected PM2.5 only, so a raw choice made in Detailed moves to corrected.
    LaunchedEffect(pickable, state.metric) {
        if (pickable.isNotEmpty() && state.metric !in pickable && state.metric in state.availableMetrics) controller.setMetric(pickable.first())
    }
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
            if (state.things.size > 1) {
                item(key = "thing-epochs") { ThingChips(state, level, controller::selectThing) }
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
                    ChartCard(state, units, controller.nowS(), level, onOpenDay = controller::openDay)
                }
            }
            item(key = "metric") {
                // One choice of many, so screen readers hear the chips as a group.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pickable.forEach { m ->
                        FilterChip(
                            selected = state.metric.id == m.id,
                            onClick = { controller.setMetric(m) },
                            label = { Text(metricLabel(m)) },
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
                        StatLine(stringResource(Res.string.history_dev_thing), state.thing ?: noValue)
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
 * Thing generations as chips. The newest is "Current", older ones "Earlier", and Developer adds
 * the first characters of the thing id. The spoken label names the full thing.
 */
@Composable
private fun ThingChips(state: HistoryUiState, level: DetailLevel, onSelect: (String) -> Unit) {
    Column {
        Text(stringResource(Res.string.history_from), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.things.forEach { thing ->
                val current = thing == state.things.last()
                val base = stringResource(if (current) Res.string.history_current else Res.string.history_earlier)
                val label = if (level.developer) "$base · ${thing.take(8)}" else base
                val chipDescription = stringResource(Res.string.history_thing_chip, label, thing)
                FilterChip(
                    selected = state.thing == thing,
                    onClick = { onSelect(thing) },
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
                    stringResource(colourInfoRes(level)),
                    pm25CorrectionInfo(),
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
        val unreadable = state.series?.unreadableSources ?: 0
        if (unreadable > 0) StatLine(stringResource(Res.string.history_read_failed), unreadable.toString())
        if (files.estimated > 0) StatLine(stringResource(Res.string.history_estimated), files.estimated.toString())
        if (files.otherThing > 0) InfoNote(pluralStringResource(Res.plurals.history_other_thing, files.otherThing, files.otherThing))
        if (files.untimed > 0) InfoNote(pluralStringResource(Res.plurals.history_untimed_files, files.untimed, files.untimed))
    }
}

/**
 * The History metric chips. Detailed and Developer offer every measured metric, corrected PM2.5 first
 * and raw second. Simple offers only the app profile's primary metrics, corrected PM2.5 only, and raw
 * only when no corrected value exists, so it is still labelled raw. A board with no primary metric
 * offers them all, so Simple is never empty.
 */
internal fun pickerMetrics(available: List<MetricSpec>, level: DetailLevel): List<MetricSpec> {
    if (level.detailed) return available
    val shown = available.filter { it.primary }.ifEmpty { available }
    return if (Metrics.PM25_CORRECTED in shown) shown - Metrics.PM25 else shown
}

/** The archive info line about PM colours. Simple never names WHO. */
internal fun colourInfoRes(level: DetailLevel): StringResource = if (level.detailed) Res.string.info_history_body5 else Res.string.info_history_body5_simple

/**
 * The picture depends on the span: a day is a ring plus hourly columns, a week hourly columns, a
 * month or a year a calendar of days plus daily or weekly columns. Touch a column to read it.
 */
@Composable
private fun ChartCard(state: HistoryUiState, units: UnitFormatter, nowS: Long, level: DetailLevel, onOpenDay: (Long) -> Unit) {
    val s = state.series
    val zone = state.zone
    var selected by remember(state.range, state.toS, state.metric) { mutableStateOf<HistoryColumn?>(null) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(metricLabel(state.metric), heading = true)
                Spacer(Modifier.width(6.dp))
                Eyebrow(units.unit(state.metric.unit), uppercase = false)
                Spacer(Modifier.weight(1f))
                if (s != null && !s.isEmpty) Eyebrow(stringResource(Res.string.history_covered, (s.coverage * 100).toInt()), uppercase = false)
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.thing == null -> HistoryStateMessage(stringResource(Res.string.history_never_connected))
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
                                    showStep = level.detailed,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        HistoryRange.MONTH, HistoryRange.YEAR -> {
                            val days = remember(s, zone) { Columns.rebinLocalDays(s, zone) }
                            CalendarHeatmap(days, state.metric, s.fromS, s.toS, onPickDay = { onOpenDay(it.startS) }, zone = zone, units = units, showStep = level.detailed)
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
                        level = level,
                    )
                    Text(
                        averagingNote(state.range, state.metric, level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SelectedColumn(selected, state, units, level.detailed)
                    Spacer(Modifier.height(8.dp))
                    Summary(s, units, level.detailed)
                }
            }
        }
    }
}

/** What the touched column says: slot, value with its mood and WHO step in words, peak, coverage, estimated or not. */
@Composable
private fun SelectedColumn(c: HistoryColumn?, state: HistoryUiState, units: UnitFormatter, stepNamed: Boolean) {
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
                // Simple names the mood only, never the WHO step.
                mood?.let { if (stepNamed) it.titleText() + " · " + it.step.labelText() else it.titleText() },
                c.max?.takeIf { it > m * 1.15f }?.let { stringResource(Res.string.history_peak_value, fmtValue(units.value(it, unit))) },
                stringResource(Res.string.history_covered, (c.coverage * 100).toInt()),
                if (c.estimated) stringResource(Res.string.history_estimated_time) else null,
            ).joinToString(" · ")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (c == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
}

@Composable
private fun Summary(s: HistorySeries, units: UnitFormatter, stepNamed: Boolean) {
    // Moods and colours read the SI value. Only the printed number converts.
    val unit = s.metric.unit
    val noValue = stringResource(Res.string.history_no_value)
    // The value colours carry the mood, so each figure also states it in words.
    val meanMood = s.mean?.let { s.metric.moodOf(it.toDouble()) }
    val peakMood = s.max?.let { s.metric.moodOf(it.toDouble()) }
    val meanState = meanMood?.let { if (stepNamed) it.titleText() + ", " + it.step.labelText() else it.titleText() }
    val peakState = peakMood?.let { if (stepNamed) it.titleText() + ", " + it.step.labelText() else it.titleText() }
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

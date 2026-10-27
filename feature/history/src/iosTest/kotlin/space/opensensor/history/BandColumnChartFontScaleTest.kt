package space.opensensor.history

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.MetricSpec
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.settings.DetailLevel
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * At large font sizes no label the History bar chart draws is clipped or drawn over another (#99).
 * The chart itself is rendered and every label it draws is recorded through [LocalChartDrawRecorder],
 * at 200 percent and at 310 percent (the largest iOS size), on 280 and 320 dp charts, in English and
 * in Arabic right to left, at Simple and Detailed.
 */
@OptIn(ExperimentalTestApi::class)
class BandColumnChartFontScaleTest {
    init {
        AirFamily.install()
    }

    private class Case(val name: String, val metric: MetricSpec, val range: HistoryRange, val spanS: Long, val peak: Float, val units: UnitFormatter = UnitFormatter.Metric)

    private class Locale(val name: String, val direction: LayoutDirection, val labels: (DetailLevel) -> List<String>)

    private val cases = listOf(
        Case("PM2.5 day", AirMetrics.PM25, HistoryRange.DAY, DAY, 40f),
        Case("PM2.5 week, off scale", AirMetrics.PM25, HistoryRange.WEEK, 7 * DAY, 180f),
        Case("PM10 month", AirMetrics.PM10, HistoryRange.MONTH, 30 * DAY, 60f),
        Case("particle count year", AirMetrics.PARTICLES_GT03, HistoryRange.YEAR, 365 * DAY, 12_000f),
        Case("temperature in °F", AirMetrics.AMBIENT_TEMP, HistoryRange.DAY, DAY, 38f, UnitFormatter(UnitSystem.IMPERIAL)),
    )

    // The guideline labels as values and values-ar hold them, the Arabic Detailed one is the widest.
    private val locales = listOf(
        Locale("en", LayoutDirection.Ltr) { if (it.detailed) listOf("WHO 24 h guideline", "24 h guideline", "24 h") else listOf("24 h guideline", "24 h") },
        Locale("ar", LayoutDirection.Rtl) {
            val simple = listOf("الحد الإرشادي لمدة 24 ساعة", "24 ساعة")
            if (it.detailed) listOf("إرشاد منظمة الصحة العالمية لمدة 24 ساعة") + simple else simple
        },
    )

    private class Setup(val case: Case, val locale: Locale, val level: DetailLevel, val fontScale: Float, val widthDp: Int) {
        override fun toString() = "${case.name}, ${locale.name}, $level, font ${fontScale}x, $widthDp dp"
    }

    private val setups = cases.flatMap { case ->
        locales.flatMap { locale ->
            listOf(DetailLevel.SIMPLE, DetailLevel.DETAILED).flatMap { level ->
                listOf(2f, 3.1f).flatMap { scale -> listOf(280, 320).map { Setup(case, locale, level, scale, it) } }
            }
        }
    }

    private fun columns(case: Case): List<HistoryColumn> {
        val n = 24
        val slot = case.spanS / n
        return (0 until n).map { i ->
            val mean = case.peak * (i + 1) / n
            HistoryColumn(startS = i * slot, endS = (i + 1) * slot, mean = mean, max = mean, coverage = 1f, estimatedShare = 0f)
        }
    }

    /** What is wrong with one drawn frame, empty when every label fits. */
    private fun problems(setup: Setup, frame: ChartDrawing, gap: Float): List<String> = buildList {
        val canvas = frame.canvas
        val plot = frame.plot
        val slack = 0.5f
        fun byKind(kind: LabelKind) = frame.labels.filter { it.kind == kind }.map { it.rect }
        val yRects = byKind(LabelKind.Y)
        val xRects = byKind(LabelKind.X)
        frame.labels.forEach {
            val r = it.rect
            if (r.left < canvas.left - slack || r.top < canvas.top - slack || r.right > canvas.right + slack || r.bottom > canvas.bottom + slack) add("${it.kind} $r leaves the canvas $canvas")
        }
        yRects.forEach { if (it.right > plot.left + slack) add("y label $it runs into the plot at ${plot.left}") }
        xRects.forEach { if (it.top < plot.bottom - slack) add("x label $it runs into the plot at ${plot.bottom}") }
        // Values rise up the axis and time runs right, each kept label clears the one before by the gap.
        yRects.zipWithNext().forEach { (lower, upper) -> if (upper.bottom + gap > lower.top + slack) add("y label $upper is closer than the gap to $lower") }
        xRects.zipWithNext().forEach { (a, b) -> if (a.right + gap > b.left + slack) add("x label $b is closer than the gap to $a") }
        if (yRects.size < 2) add("only ${yRects.size} y labels")
        if (xRects.size < 2) add("only ${xRects.size} x labels")
        val hasGuideline = setup.case.metric == AirMetrics.PM25 || setup.case.metric == AirMetrics.PM10
        if (hasGuideline && byKind(LabelKind.GUIDE).size != 1) add("the guideline line has no label")
    }.map { "$setup: $it" }

    @Test
    fun noDrawnLabelIsClippedOrOverlappedAtLargeFontSizes() = runComposeUiTest {
        var index by mutableStateOf(0)
        var frame: ChartDrawing? = null
        var gap = 0f
        setContent {
            val setup = setups[index]
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, setup.fontScale),
                LocalLayoutDirection provides setup.locale.direction,
                LocalChartDrawRecorder provides { frame = it },
            ) {
                gap = with(LocalDensity.current) { LABEL_GAP.toPx() }
                MaterialTheme {
                    val case = setup.case
                    BandColumnChart(
                        columns(case), case.metric, case.range, 0, case.spanS, selected = null, onSelect = {},
                        modifier = Modifier.width(setup.widthDp.dp), zone = TimeZone.UTC, units = case.units,
                        level = setup.level, guideLabels = setup.locale.labels(setup.level),
                    )
                }
            }
        }
        waitForIdle()
        val found = setups.indices.flatMap { i ->
            if (i > 0) {
                // A new frame for the next setup, so a stale one is never checked.
                frame = null
                index = i
                waitForIdle()
            }
            val drawn = frame ?: return@flatMap listOf("${setups[i]}: nothing was drawn")
            problems(setups[i], drawn, gap)
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun theLabelsReallyGrowWithTheFontSize() = runComposeUiTest {
        // Guards the test above, the labels it checks are measured at the scaled size, not a fixed px size.
        var small = 0
        var large = 0
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) { small = rememberTextMeasurer().measure("100", chartTextStyle(Color.Black, AXIS_LABEL_SP)).size.height }
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) { large = rememberTextMeasurer().measure("100", chartTextStyle(Color.Black, AXIS_LABEL_SP)).size.height }
        }
        waitForIdle()
        assertTrue(large > small * 1.6f, "fontScale 2 label is $large px, fontScale 1 is $small px")
    }

    @Test
    fun theLongArabicGuidelineLabelWrapsOrFallsBack() = runComposeUiTest {
        // At 200 percent on 320 dp the Arabic Detailed label is wider than the plot. It is drawn on two
        // lines or replaced by a shorter one, never past the edge.
        var frame: ChartDrawing? = null
        val ar = locales.last()
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalChartDrawRecorder provides { frame = it },
            ) {
                MaterialTheme {
                    BandColumnChart(
                        columns(cases[0]), AirMetrics.PM25, HistoryRange.DAY, 0, DAY, selected = null, onSelect = {},
                        modifier = Modifier.width(320.dp), zone = TimeZone.UTC, level = DetailLevel.DETAILED, guideLabels = ar.labels(DetailLevel.DETAILED),
                    )
                }
            }
        }
        waitForIdle()
        val drawn = checkNotNull(frame)
        val guide = drawn.labels.single { it.kind == LabelKind.GUIDE }
        val oneLine = guide.layout.lineCount == 1 && guide.layout.layoutInput.text.text == ar.labels(DetailLevel.DETAILED).first()
        assertTrue(!oneLine, "the full label fits on one line, the case no longer tests wrapping")
        assertTrue(guide.rect.right <= drawn.canvas.right + 0.5f && guide.rect.left >= drawn.plot.left - 0.5f, "${guide.rect} leaves the plot ${drawn.plot}")
    }

    @Test
    fun aGuidelineLabelTooWideForOneLineIsDrawnInFullOnTwo() = runComposeUiTest {
        // The plot is narrower than the full label on one line but wider than each half, so the full
        // label wraps to two lines. The short fallback is never needed.
        val texts = listOf("WHO 24 h guideline", "24 h")
        var fitted: TextLayoutResult? = null
        var oneLineWidth = 0
        setContent {
            val measurer = rememberTextMeasurer()
            val style = chartTextStyle(Color.Black, AXIS_LABEL_SP)
            oneLineWidth = measurer.measure(texts.first(), style, maxLines = 1).size.width
            fitted = fitGuideLabel(measurer, texts, style, width = oneLineWidth * 0.7f, height = 10_000f)
        }
        waitForIdle()
        val label = checkNotNull(fitted) { "no label fits" }
        assertEquals(texts.first(), label.layoutInput.text.text, "the short fallback was chosen over the wrapped full label")
        assertEquals(2, label.lineCount)
        assertTrue(label.size.width <= oneLineWidth * 0.7f, "the wrapped label is ${label.size.width} px wide")
    }

    @Test
    fun everyBarStillHasItsNodeAtTwiceTheFontSize() = runComposeUiTest {
        val day = (0 until 24).map { i -> HistoryColumn(i * HOUR, (i + 1) * HOUR, mean = 5f + i, max = 5f + i, coverage = 1f, estimatedShare = 0f) }
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                MaterialTheme {
                    BandColumnChart(day, AirMetrics.PM25, HistoryRange.DAY, 0, DAY, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
                }
            }
        }
        val bars = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNodes()
        assertEquals(24, bars.size)
        assertTrue(bars.all { it.size.width > 0 && it.size.height > 0 }, "a bar node collapsed")
    }

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
    }
}

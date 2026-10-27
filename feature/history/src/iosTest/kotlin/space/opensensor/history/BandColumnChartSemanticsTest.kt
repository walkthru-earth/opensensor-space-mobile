package space.opensensor.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.HistoryColumn
import space.opensensor.data.history.HistoryRange
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TalkBack and VoiceOver walk the History bar chart one bar at a time (#99): slot, value with unit,
 * mood, then the WHO step above Simple. Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class BandColumnChartSemanticsTest {
    private fun hour(index: Int, mean: Float?, estimated: Boolean = false) = HistoryColumn(
        startS = index * HOUR,
        endS = (index + 1) * HOUR,
        mean = mean,
        max = mean,
        coverage = if (mean == null) 0f else 1f,
        estimatedShare = if (estimated) 1f else 0f,
    )

    // 22 µg/m³ is within interim target 4 (Fair), 40 within interim target 2 (Poor).
    private val day = listOf(hour(0, 22f), hour(1, null), hour(2, 40f, estimated = true)) + (3 until 24).map { hour(it, null) }

    @Test
    fun simpleReadsEachBarWithItsMoodAndNeverNamesWho() = runComposeUiTest {
        setContent {
            MaterialTheme {
                BandColumnChart(day, AirMetrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
            }
        }
        onNodeWithContentDescription("00:00, 22.0 µg/m³, Fair").assertExists()
        onNodeWithContentDescription("02:00, 40.0 µg/m³, Poor, estimated time").assertExists()
        onAllNodesWithContentDescription("WHO", substring = true).assertCountEquals(0)
        // Empty hours are left out of the swipe order. Each bar is a button, like a calendar day.
        onAllNodes(hasClickAction()).assertCountEquals(2)
        onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(2)
    }

    @Test
    fun walkingTheTreeFindsOneNodePerBarInTimeOrder() = runComposeUiTest {
        // Every slot with a reading is one button node in time order, an empty slot is none.
        val hours = listOf(hour(0, 3f), hour(1, 12f), hour(2, null), hour(3, 22f), hour(4, 40f), hour(5, 60f), hour(6, 120f)) + (7 until 24).map { hour(it, null) }
        setContent {
            MaterialTheme {
                BandColumnChart(hours, AirMetrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
            }
        }
        val spoken = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Button) {
                spoken += node.config[SemanticsProperties.ContentDescription].joinToString()
            }
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        assertEquals(
            listOf(
                "00:00, 3.0 µg/m³, Fresh air",
                "01:00, 12.0 µg/m³, Fresh air",
                "03:00, 22.0 µg/m³, Fair",
                "04:00, 40.0 µg/m³, Poor",
                "05:00, 60.0 µg/m³, Smoky",
                "06:00, 120 µg/m³, Thick smoke",
            ),
            spoken,
        )
    }

    @Test
    fun detailedAddsTheWhoStepAfterTheMood() = runComposeUiTest {
        setContent {
            MaterialTheme {
                BandColumnChart(day, AirMetrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.DETAILED)
            }
        }
        onNodeWithContentDescription("00:00, 22.0 µg/m³, Fair, Within WHO interim target 4").assertExists()
        onNodeWithContentDescription("02:00, 40.0 µg/m³, Poor, Within WHO interim target 2, estimated time").assertExists()
    }

    @Test
    fun activatingABarSelectsIt() = runComposeUiTest {
        var picked: HistoryColumn? = null
        setContent {
            MaterialTheme {
                BandColumnChart(day, AirMetrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = day[2], onSelect = { picked = it }, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
            }
        }
        onNodeWithContentDescription("02:00, 40.0 µg/m³, Poor, estimated time").assertIsSelected()
        onNodeWithContentDescription("00:00, 22.0 µg/m³, Fair").assertIsNotSelected().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(day[0], picked)
    }

    @Test
    fun aFileStripNamesItsBarsFromTheCaller() = runComposeUiTest {
        val files = listOf(hour(0, 12f), hour(1, 30f)).map { it.copy(startS = it.startS / HOUR, endS = it.endS / HOUR) }
        setContent {
            MaterialTheme {
                BandColumnChart(
                    files, AirMetrics.PM25, HistoryRange.DAY, 0, 2, selected = null, onSelect = {}, zone = TimeZone.UTC,
                    xLabels = listOf(0L to "first"), level = DetailLevel.SIMPLE, barSlot = { c -> "file ${c.startS + 1}" },
                )
            }
        }
        onNodeWithContentDescription("file 1, 12.0 µg/m³, Fresh air").assertExists()
        onNodeWithContentDescription("file 2, 30.0 µg/m³, Getting hazy").assertExists()
    }

    private companion object {
        const val HOUR = 3_600L
    }
}

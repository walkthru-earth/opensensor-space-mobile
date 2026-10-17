package earth.walkthru.opensensor.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import earth.walkthru.aqsync.data.history.HistoryColumn
import earth.walkthru.aqsync.data.history.HistoryRange
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.datetime.TimeZone
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
                BandColumnChart(day, Metrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
            }
        }
        onNodeWithContentDescription("00:00, 22.0 µg/m³, Fair").assertExists()
        onNodeWithContentDescription("02:00, 40.0 µg/m³, Poor, estimated time").assertExists()
        onAllNodesWithContentDescription("WHO", substring = true).assertCountEquals(0)
        // Empty hours are left out of the swipe order.
        onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun detailedAddsTheWhoStepAfterTheMood() = runComposeUiTest {
        setContent {
            MaterialTheme {
                BandColumnChart(day, Metrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = null, onSelect = {}, zone = TimeZone.UTC, level = DetailLevel.DETAILED)
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
                BandColumnChart(day, Metrics.PM25, HistoryRange.DAY, 0, 24 * HOUR, selected = day[2], onSelect = { picked = it }, zone = TimeZone.UTC, level = DetailLevel.SIMPLE)
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
                    files, Metrics.PM25, HistoryRange.DAY, 0, 2, selected = null, onSelect = {}, zone = TimeZone.UTC,
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

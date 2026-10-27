package space.opensensor.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.HistoryColumn
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.units.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #119. A screen reader steps through the Day ring one hour at a time after the day summary, and the
 * hour nodes sit on the ring inside its bounds, also at 200 percent font size.
 */
@OptIn(ExperimentalTestApi::class)
class DayRingSemanticsTest {
    init {
        AirFamily.install()
    }

    private fun hour(index: Int, mean: Float?, estimated: Boolean = false) = HistoryColumn(
        startS = index * HOUR,
        endS = (index + 1) * HOUR,
        mean = mean,
        max = mean,
        coverage = if (mean == null) 0f else 1f,
        estimatedShare = if (estimated) 1f else 0f,
    )

    // 3 µg/m³ is within the guideline, 22 within interim target 4, 40 within interim target 2. 05:00 is empty.
    private val day = (0 until 24).map { h ->
        when (h) {
            0 -> hour(h, 3f)
            5 -> hour(h, null)
            12 -> hour(h, 40f, estimated = true)
            else -> hour(h, 22f)
        }
    }

    private data class Spoken(val text: String, val order: Float?, val node: SemanticsNode)

    private fun described(root: SemanticsNode): List<Spoken> {
        val out = ArrayList<Spoken>()
        fun walk(node: SemanticsNode) {
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.let {
                out += Spoken(it.joinToString(), node.config.getOrElseNullable(SemanticsProperties.TraversalIndex) { null }, node)
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** Renders the ring, checks the summary and the hour nodes, and returns what each hour says in order. */
    private fun render(showStep: Boolean, fontScale: Float = 1f): List<String> {
        var said = emptyList<String>()
        runComposeUiTest {
            setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    MaterialTheme {
                        Box(Modifier.width(360.dp)) {
                            DayRing(day, AirMetrics.PM25, 0, DAY, mean = 20f, coverage = 0.96f, nowS = null, selected = null, zone = TimeZone.UTC, units = UnitFormatter.Metric, showStep = showStep)
                        }
                    }
                }
            }
            waitForIdle()
            val spoken = described(onRoot(useUnmergedTree = true).fetchSemanticsNode())
            val summary = spoken.single { it.order == -1f }
            assertTrue("through the day" in summary.text, summary.text)
            val hours = spoken.filter { (it.order ?: -1f) >= 0f }.sortedBy { it.order }
            assertEquals(23, hours.size, hours.joinToString { it.text })
            assertEquals("00:00", hours.first().text.substringBefore(","))
            assertEquals("23:00", hours.last().text.substringBefore(","))
            assertTrue(hours.none { it.text.startsWith("05:00") }, "an empty hour has no node")
            val ring = summary.node.boundsInRoot
            for (h in hours) {
                val b = h.node.boundsInRoot
                assertTrue(b.width > 0f && ring.contains(b.center), "${h.text} at $b is outside the ring $ring")
            }
            said = hours.map { it.text }
        }
        return said
    }

    @Test
    fun detailedReadsEachHourWithItsWhoStep() {
        val said = render(showStep = true)
        assertEquals("00:00, 3.0 µg/m³, Fresh air, Within the WHO guideline", said.first())
        assertTrue("12:00, 40.0 µg/m³, Poor, Within WHO interim target 2, estimated time" in said, said.joinToString("\n"))
    }

    @Test
    fun simpleReadsEachHourAndNeverNamesWho() {
        val said = render(showStep = false)
        assertEquals("00:00, 3.0 µg/m³, Fresh air", said.first())
        assertTrue(said.none { "WHO" in it }, said.joinToString("\n"))
    }

    @Test
    fun hourNodesStayOnTheRingAtTwiceTheFontSize() {
        render(showStep = false, fontScale = 2f)
    }

    private companion object {
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
    }
}

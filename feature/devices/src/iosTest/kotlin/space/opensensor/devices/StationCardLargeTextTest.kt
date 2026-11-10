package space.opensensor.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import space.opensensor.data.history.LatestDay
import space.opensensor.designsystem.nowEpochMillis
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * At 200 percent font on a 320 dp phone a station card with its archive day line, its mood, value,
 * unit, date, file count and synced age, its seen line and the Finish setup chip keeps every text
 * whole, inside the card and apart from the others (#117). A failed link keeps its error words whole
 * too. Rendered headless on the iOS simulator.
 *
 * The ages tick from the system clock, so both sit 45 minutes back from the test start, two digits
 * wide, and the checks match any minute count.
 */
@OptIn(ExperimentalTestApi::class)
class StationCardLargeTextTest {
    init {
        AirFamily.install()
    }

    // 2026-11-09 UTC, an unhealthy day so the mood word is one of the longest.
    private val day = LatestDay(startEpochS = 1_794_182_400, mean = 148.9f, coverage = 0.92f, metric = AirMetrics.PM25_CORRECTED, displayStep = 0.1)
    private val agoMs = nowEpochMillis() - 45 * 60_000L - 30_000L
    private val glance = ArchiveGlance(files = 124, lastSyncedMs = agoMs, lastDataUtcNs = 1_794_200_000_000_000_000, lastDay = day)
    private val row = DeviceRow(
        id = "AA:BB:CC:DD:EE:01",
        name = "Balcony sensor in Accra",
        thing = null,
        rssi = -60,
        bonded = true,
        known = true,
        wifi = WifiTag.WITH_ACCESS,
        lastSeenMs = agoMs,
        archive = glance,
    )

    private val hasAnyText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    // Any minute count, so a slow runner that crosses into the next minute still matches.
    private fun hasTextMatching(regex: Regex) = SemanticsMatcher("text matches $regex") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { regex.containsMatchIn(it.text) }
    }

    private fun ComposeUiTest.show(card: @Composable () -> Unit) {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).testTag("card")) { card() } }
            }
        }
    }

    private fun DpRect.overlaps(other: DpRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private fun ComposeUiTest.assertEveryTextFits(minTexts: Int) {
        val card = onNodeWithTag("card").getUnclippedBoundsInRoot()
        val nodes = onAllNodes(hasAnyText, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue(count >= minTexts, "only $count texts on the card")
        val boxes = (0 until count).map { i ->
            val node = nodes[i]
            val config = node.fetchSemanticsNode().config
            val text = config[SemanticsProperties.Text].joinToString()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= card.left && bounds.right <= card.right, "'$text' runs past the card, $bounds in $card")
            val layouts = mutableListOf<TextLayoutResult>()
            if (SemanticsActions.GetTextLayoutResult in config) config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.let {
                val ellipsized = (0 until it.lineCount).any { line -> it.isLineEllipsized(line) }
                assertTrue(!it.didOverflowHeight && !ellipsized, "'$text' is cut")
            }
            text to bounds
        }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val (a, aBox) = boxes[i]
                val (b, bBox) = boxes[j]
                assertTrue(!aBox.overlaps(bBox), "'$a' overlaps '$b'")
            }
        }
    }

    @Test
    fun aKnownCardKeepsItsDayLineAndSetupChipWholeAtDoubleText() = runComposeUiTest {
        show { KnownRow(row.copy(rssi = null, needsSetup = true), DetailLevel.DETAILED, onClick = {}) }
        // The seen line and the day line that ends with the synced age are both on the card.
        onAllNodes(hasTextMatching(Regex("""\d+ min ago""")), useUnmergedTree = true).assertCountEquals(2)
        assertEveryTextFits(minTexts = 5)
    }

    @Test
    fun aNearbyCardKeepsItsTagsAndDayLineWholeAtDoubleText() = runComposeUiTest {
        show { NearbyRow(row, DetailLevel.DETAILED, onClick = {}) }
        onAllNodes(hasTextMatching(Regex("""synced \d+ min ago""")), useUnmergedTree = true).assertCountEquals(1)
        assertEveryTextFits(minTexts = 4)
    }

    @Test
    fun aFailedLinkKeepsItsErrorWordsWholeAtDoubleText() = runComposeUiTest {
        show { ActiveRow(ActiveLink(row.id, row.name, LinkPhase.FAILED), onClick = {}) }
        assertEveryTextFits(minTexts = 2)
    }
}

package space.opensensor.today

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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * At 200 percent font on a 320 dp phone the hero, particle and storage cards keep every number,
 * unit and label whole and apart (#117). No text runs past the card, no line is cut, and no two
 * texts overlap. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class LargeTextFitTest {
    init {
        AirFamily.install()
    }

    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
    private val sample = LiveSample.parse(
        """{"pms":4,"pm25":148.9,"pm1":88.0,"pm10":201.0,"c25":180.0,"rh":55.0,"t":31.5,"n03":12345,"n05":6789,"n1":2345,"n25":678}""",
    )
    private val reading = TodayUiState(TodaySnapshot(info = waveshare, live = sample), DetailLevel.DETAILED).reading

    private val hasAnyText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

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
            val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= card.left && bounds.right <= card.right, "'$text' runs past the card, $bounds in $card")
            val layouts = mutableListOf<TextLayoutResult>()
            val config = node.fetchSemanticsNode().config
            if (SemanticsActions.GetTextLayoutResult in config) config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // A cut line shows as height overflow or an ellipsis. Width overflow is not checked, the
            // semantics layout reports it for one line text that fits.
            layouts.firstOrNull()?.let {
                val ellipsized = (0 until it.lineCount).any { line -> it.isLineEllipsized(line) }
                assertTrue(!it.didOverflowHeight && !ellipsized, "'$text' is cut")
                // A number never breaks over lines.
                if (text.all { c -> c.isDigit() || c == '.' }) assertTrue(it.lineCount == 1, "'$text' breaks over ${it.lineCount} lines")
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
    fun theHeroKeepsTheNumberUnitAndMoodWholeAtDoubleText() = runComposeUiTest {
        show { MetricHeroCard(reading, receivedAtEpochMs = null, level = DetailLevel.DETAILED) }
        assertEveryTextFits(minTexts = 4)
        // The peacock moves above the number, so the value, unit and name get the whole card width.
        val card = onNodeWithTag("card").getUnclippedBoundsInRoot()
        val body = onNode(hasText("m³", substring = true)).getUnclippedBoundsInRoot()
        assertTrue(body.right - body.left > (card.right - card.left) * 0.7f, "the reading is squeezed beside the peacock, $body in $card")
    }

    @Test
    fun theParticleCountsWrapInsteadOfOverlappingAtDoubleText() = runComposeUiTest {
        show { ParticlesCard(reading) }
        assertEveryTextFits(minTexts = 8)
    }

    @Test
    fun theStorageCountersWrapAndAProblemCountHasAnIconAtDoubleText() = runComposeUiTest {
        val status = DeviceStatus(fin = 1234, buf = 56, open = 1, drop = 7, err = 3, miss = 12, sd = 1, intervalSeconds = 900)
        show { StorageCard(status, receivedAtEpochMs = null, level = DetailLevel.DETAILED) }
        assertEveryTextFits(minTexts = 8)
        // Each non zero problem counter carries a warning icon, so colour is never the only cue.
        val icons = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
        assertTrue(icons.fetchSemanticsNodes().size >= 3, "a warning icon beside each problem count")
    }
}

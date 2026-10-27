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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A station card has one tap, which opens the sensor, and no Forget or other nested button
 * (#117). At 200 percent font on a 320 dp phone its tags wrap onto new lines instead of running
 * past the card edge. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class StationCardTest {
    private val row = DeviceRow(
        id = "AA:BB:CC:DD:EE:01",
        name = "Balcony sensor in Accra",
        thing = null,
        rssi = -60,
        bonded = true,
        known = true,
        wifi = WifiTag.WITH_ACCESS,
        lastSeenMs = null,
        archive = null,
    )

    private val hasAnyText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private fun ComposeUiTest.show(fontScale: Float, card: @Composable () -> Unit) {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme { Box(Modifier.width(320.dp).testTag("card")) { card() } }
            }
        }
    }

    private fun ComposeUiTest.assertOneTapAndNoForget() {
        assertEquals(1, onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size, "one tap on the card")
        assertEquals(0, onAllNodes(hasText("Forget", substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun ComposeUiTest.assertTextStaysInside() {
        val card = onNodeWithTag("card").getUnclippedBoundsInRoot()
        val texts = onAllNodes(hasAnyText, useUnmergedTree = true)
        val count = texts.fetchSemanticsNodes().size
        assertTrue(count >= 2, "the name and a tag are on screen")
        for (i in 0 until count) {
            val bounds = texts[i].getUnclippedBoundsInRoot()
            assertTrue(bounds.right <= card.right, "text ends at ${bounds.right}, card at ${card.right}")
            // A tag is one short word or two, so it moves to a new line whole instead of breaking.
            val config = texts[i].fetchSemanticsNode().config
            val text = config[SemanticsProperties.Text].joinToString()
            if (text != row.name) {
                val layouts = mutableListOf<TextLayoutResult>()
                config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                assertEquals(1, layouts.single().lineCount, "'$text' breaks over lines")
            }
        }
    }

    @Test
    fun aNearbyCardHasOneTapAndItsTagsWrapAtLargeText() = runComposeUiTest {
        show(2f) { NearbyRow(row, DetailLevel.SIMPLE, onClick = {}) }
        assertOneTapAndNoForget()
        assertTextStaysInside()
    }

    @Test
    fun aKnownCardHasOneTapAndItsTagWrapsAtLargeText() = runComposeUiTest {
        show(2f) { KnownRow(row.copy(rssi = null), DetailLevel.SIMPLE, onClick = {}) }
        assertOneTapAndNoForget()
        assertTextStaysInside()
    }
}

package space.opensensor.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.OpenSensorTheme
import space.opensensor.designsystem.Peacock
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.rememberBrandTypography
import space.opensensor.publicfeed.FeedProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sample data tag on the peek is shown whole at 200 percent font on a 280 dp phone, over the
 * chart title and beside the first run peacock, and the chart title beside it is not cut (#116).
 * The Arabic tag, in Cairo and right to left, is shown whole in the same two places (#114).
 * Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class SampleTagFontScaleTest {
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")

    private fun ComposeUiTest.atLargeFont(content: @Composable () -> Unit) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 2f)) {
            MaterialTheme {
                // The sheet keeps 20 dp each side, as in SheetContent.
                Column(Modifier.width(280.dp).padding(horizontal = 20.dp)) { content() }
            }
        }
    }

    /** values-ar discover_sample_data. DiscoverLabelsLocaleTest checks the catalogue still says this. */
    private val arabicSample = "بيانات تجريبية"

    private fun ComposeUiTest.inArabicAtLargeFont(content: @Composable () -> Unit) = setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(density = 2f, fontScale = 2f),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            OpenSensorTheme(darkTheme = false, typography = rememberBrandTypography(arabicScript = true), reduceMotion = true) {
                Column(Modifier.width(280.dp).padding(horizontal = 20.dp)) { content() }
            }
        }
    }

    private fun ComposeUiTest.layoutOf(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun ComposeUiTest.assertWhole(text: String) {
        val layout = layoutOf(text)
        assertEquals(1, layout.lineCount, "$text is on one line")
        assertFalse(layout.hasVisualOverflow, "$text is cut")
        assertFalse(layout.isLineEllipsized(0), "$text is ellipsized")
    }

    @Test
    fun theTagSitsWholeOverTheChartTitleAndTheTitleIsNotCut() = runComposeUiTest {
        var strings: DiscoverStrings? = null
        atLargeFont { ChartTitle(pm25, discoverStrings().also { strings = it }, DiscoverUiState(), sample = true) }
        val sample = strings!!.sampleData
        assertWhole(sample)
        assertWhole("µg/m³")
        val tag = onNode(hasText(sample), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = onNode(hasText("µg/m³", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(tag.bottom <= title.top, "the tag $tag is on its own line above the title $title")
    }

    @Test
    fun theTagSitsWholeBesideTheFirstRunPeacock() = runComposeUiTest {
        var strings: DiscoverStrings? = null
        atLargeFont { FirstRunCard(discoverStrings().also { strings = it }, sample = true, onAddSensor = {}, onExplore = {}) }
        assertWhole(strings!!.sampleData)
    }

    @Test
    fun theArabicTagSitsWholeOverTheChartTitle() = runComposeUiTest {
        // The sheet width, as ChartTitle gives the tag its own line.
        inArabicAtLargeFont { SampleTag(arabicSample) }
        assertWhole(arabicSample)
    }

    @Test
    fun theArabicTagSitsWholeBesideTheFirstRunPeacock() = runComposeUiTest {
        // The first run card's row, the 80 dp peacock and a 12 dp gap before the tag's column.
        inArabicAtLargeFont {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Peacock(size = 80.dp, expression = PeacockExpression.WELCOME, animate = false)
                Column(Modifier.weight(1f)) { SampleTag(arabicSample) }
            }
        }
        assertWhole(arabicSample)
    }
}

package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Labels fit to one line and no text splits a word (docs/cross-platform-guide.md, "Text scaling and
 * wrapping", #142, #143, #144). Rendered on the iOS simulator with the bundled Quicksand and Cairo
 * faces, at font scale 1.0, 2.0 and 3.1, on 280 and 360 dp, in English and in Arabic right to left.
 */
@OptIn(ExperimentalTestApi::class)
class FitTextLayoutTest {
    private class Setup(val fontScale: Float, val width: Dp, val arabic: Boolean) {
        override fun toString() = "scale=$fontScale width=$width ${if (arabic) "ar" else "en"}"
    }

    private val setups = listOf(1f, 2f, 3.1f).flatMap { scale ->
        listOf(280.dp, 360.dp).flatMap { width -> listOf(false, true).map { Setup(scale, width, it) } }
    }

    private val english = listOf("Discover", "My devices", "Settings")
    private val arabic = listOf("استكشاف", "أجهزتي", "الإعدادات")

    @Composable
    private fun Scene(setup: Setup, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(2f, setup.fontScale),
            LocalLayoutDirection provides if (setup.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            OpenSensorTheme(darkTheme = false, typography = rememberBrandTypography(arabicScript = setup.arabic), reduceMotion = true) {
                Box(Modifier.width(setup.width)) { content() }
            }
        }
    }

    private fun ComposeUiTest.layoutOf(text: String): TextLayoutResult = onNode(hasText(text), useUnmergedTree = true).layout()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /**
     * The size the text was drawn at, in px. The layout input keeps the style's own size under
     * autoSize, so the first line's width is compared with the same words measured at 100 px.
     */
    private fun TextLayoutResult.fontPx(): Float {
        val measurer = TextMeasurer(createFontFamilyResolver(), layoutInput.density, layoutInput.layoutDirection)
        val firstLine = layoutInput.text.subSequence(0, getLineEnd(0, visibleEnd = true))
        val ref = with(layoutInput.density) {
            measurer.measure(firstLine, layoutInput.style.copy(fontSize = 100f.toSp()), softWrap = false)
        }
        return 100f * (getLineRight(0) - getLineLeft(0)) / (ref.getLineRight(0) - ref.getLineLeft(0))
    }

    @Test
    fun bottomBarLabelsStayOnOneLineAtNoLessThanTheirOneScaleSize() {
        for (setup in setups) {
            val labels = if (setup.arabic) arabic else english
            runComposeUiTest {
                lateinit var style: TextStyle
                setContent {
                    Scene(setup) {
                        style = MaterialTheme.typography.labelMedium
                        BrandBottomBar(labels.map { BottomBarTab(it, it, AppIcons.Outlined.Map) }, selected = labels[1], onSelect = {})
                    }
                }
                val oneScalePx = style.fontSize.value * 2f
                for (label in labels) {
                    val layout = layoutOf(label)
                    assertEquals(1, layout.lineCount, "$label lines at $setup")
                    assertFalse(layout.hasVisualOverflow, "$label overflows at $setup")
                    assertTrue(layout.fontPx() >= oneScalePx - 0.5f, "$label ${layout.fontPx()} px >= 1.0 size $oneScalePx px at $setup")
                }
            }
        }
    }

    @Test
    fun aLabelThatFitsKeepsTheUsersFullSize() = runComposeUiTest {
        setContent { Scene(Setup(2f, 360.dp, arabic = false)) { FitText("Map", style = TextStyle(fontSize = 12.sp)) } }
        val layout = layoutOf("Map")
        assertEquals(48f, layout.fontPx(), 0.5f, "12 sp at scale 2.0 and density 2")
    }

    @Test
    fun sectionTabsNeverSplitAWord() {
        val labels = listOf("Today", "History", "Files", "Device")
        val arabicLabels = listOf("اليوم", "السجل", "الملفات", "الجهاز")
        for (setup in setups) {
            val shown = if (setup.arabic) arabicLabels else labels
            runComposeUiTest {
                lateinit var style: TextStyle
                setContent {
                    Scene(setup) {
                        style = MaterialTheme.typography.titleSmall
                        SectionTabs(shown, selectedIndex = 0, onSelect = {})
                    }
                }
                // Tabs that cannot hold their labels at the floor scroll instead of shrinking further.
                val floorPx = style.fontSize.value * 2f * FitTextLimits.FloorFraction
                for (label in shown) {
                    val layout = layoutOf(label)
                    assertEquals(1, layout.lineCount, "$label lines at $setup")
                    assertFalse(layout.hasVisualOverflow, "$label cut at $setup")
                    assertFalse(breaksInsideWord(layout), "$label splits at $setup")
                    assertTrue(layout.fontPx() >= floorPx - 0.5f, "$label ${layout.fontPx()} px >= floor $floorPx px at $setup")
                }
            }
        }
    }

    @Test
    fun aStationTitleOrAScreenTitleNeverSplitsAWord() {
        // The station name beside the header actions (#143) and the Arabic My devices title (#144).
        for (setup in setups) {
            val title = if (setup.arabic) "أجهزتي" else "AQ-7500"
            runComposeUiTest {
                setContent { Scene(setup) { Box(Modifier.width(110.dp)) { FitText(title, style = MaterialTheme.typography.headlineMedium) } } }
                val layout = layoutOf(title)
                assertEquals(1, layout.lineCount, "$title lines at $setup")
                assertFalse(layout.hasVisualOverflow, "$title cut at $setup")
            }
        }
    }

    @Test
    fun bodyTextAtLargeScaleBreaksOnlyBetweenWords() {
        val en = "Notifications appear when synchronisation of every archive completes successfully overnight"
        val ar = "تظهر الإشعارات عندما تكتمل مزامنة جميع الأرشيفات بنجاح أثناء الليل"
        for (setup in setups.filter { it.fontScale == 3.1f }) {
            val text = if (setup.arabic) ar else en
            runComposeUiTest {
                lateinit var style: TextStyle
                setContent {
                    Scene(setup) {
                        style = MaterialTheme.typography.bodyMedium
                        BodyText(text, style = style)
                    }
                }
                val layout = layoutOf(text)
                assertTrue(layout.lineCount > 1, "wraps at $setup")
                assertFalse(breaksInsideWord(layout), "line ends ${(0 until layout.lineCount).map { layout.getLineEnd(it) }} at $setup")
                // Full user size unless a single word is wider than the line.
                assertTrue(layout.fontPx() >= style.fontSize.value * 2f, "body keeps at least its 1.0 size at $setup")
            }
        }
    }

    @Test
    fun aWordWiderThanTheLineShrinksInsteadOfSplitting() {
        for (setup in setups.filter { it.fontScale == 3.1f && it.width == 280.dp }) {
            val text = if (setup.arabic) "تتم المزامنة والمسؤوليات" else "Archive synchronisation"
            runComposeUiTest {
                lateinit var style: TextStyle
                setContent {
                    Scene(setup) {
                        style = MaterialTheme.typography.bodyMedium
                        Box(Modifier.width(150.dp)) { BodyText(text, style = style) }
                    }
                }
                val layout = layoutOf(text)
                assertFalse(breaksInsideWord(layout), "line ends ${(0 until layout.lineCount).map { layout.getLineEnd(it) }} at $setup")
                assertTrue(layout.fontPx() < style.fontSize.value * 2f * setup.fontScale, "shrinks below the user's size at $setup")
            }
        }
    }

    @Test
    fun aLabelTooLongForItsFloorWrapsBetweenWordsAtTheFloor() = runComposeUiTest {
        val text = "Sync every sensor now"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                MaterialTheme { Column(Modifier.width(90.dp)) { FitText(text, style = TextStyle(fontSize = 14.sp)) } }
            }
        }
        val layout = layoutOf(text)
        assertEquals(2, layout.lineCount)
        assertFalse(breaksInsideWord(layout))
        assertEquals(14f * 2f * FitTextLimits.FloorFraction, layout.fontPx(), 0.5f, "wraps at the floor, never smaller")
    }

    @Test
    fun aCutLabelStillReadsInFull() = runComposeUiTest {
        val text = "Particulate matter two point five micrometres"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                MaterialTheme { Box(Modifier.width(60.dp)) { FitText(text, style = TextStyle(fontSize = 14.sp), maxLines = 1) } }
            }
        }
        onNode(hasText(text), useUnmergedTree = true).assert(hasText(text))
    }
}

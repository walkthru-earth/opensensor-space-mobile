package space.opensensor.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import space.opensensor.airquality.MoodBlock
import space.opensensor.airquality.WhoStep
import kotlin.test.Test

/**
 * The semantics the shared components promise to TalkBack and VoiceOver (docs/cross-platform-guide.md,
 * "Accessibility"). Rendered headless on the iOS simulator with the plain Material theme, so no font or
 * string resource has to load.
 */
@OptIn(ExperimentalTestApi::class)
class SemanticsTest {
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** The layout of the first text node showing [text]. */
    private fun ComposeUiTest.textLayout(text: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        val node = onAllNodes(hasText(text), useUnmergedTree = true)[0].fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    @Test
    fun bundledPeacockPortraitsLoadAndKeepTheirSpokenState() = runComposeUiTest {
        setContent {
            Column {
                PeacockExpression.entries.forEach { expression ->
                    PeacockIcon(expression, Modifier.size(48.dp), contentDescription = expression.name)
                }
                PeacockIcon(PeacockExpression.CONFUSED, Modifier.size(48.dp).testTag("decorative"))
            }
        }
        PeacockExpression.entries.forEach { expression ->
            onNodeWithContentDescription(expression.name).assertExists()
        }
        onNodeWithTag("decorative").assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun aSectionCardTitleIsAHeadingReadAsWritten() = runComposeUiTest {
        setContent {
            MaterialTheme { SectionCard(title = "Wi-Fi") { Text("Body") } }
        }
        onNodeWithText("WI-FI").assert(isHeading).assert(hasContentDescription("Wi-Fi"))
    }

    @Test
    fun anEyebrowThatIsNotATitleIsNotAHeading() = runComposeUiTest {
        setContent {
            MaterialTheme { Eyebrow("Battery") }
        }
        onNodeWithText("BATTERY").assert(!isHeading)
    }

    @Test
    fun aStatLineReadsAsOneElement() = runComposeUiTest {
        setContent {
            MaterialTheme { StatLine(label = "Battery", value = "80") }
        }
        onNodeWithText("Battery").assert(hasText("80"))
    }

    @Test
    fun bottomBarTabsAreTabsWithASelectedStateAnd48DpBoundsAnd8DpSpacing() = runComposeUiTest {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density = 2f, fontScale = 1f),
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        BrandBottomBar(
                            tabs = listOf(
                                BottomBarTab("today", "Today", AppIcons.Sync),
                                BottomBarTab("map", "Map", AppIcons.Outlined.Map),
                            ),
                            selected = "today",
                            onSelect = {},
                        )
                    }
                }
            }
        }
        onNodeWithText("Today").assert(hasRole(Role.Tab)).assertIsSelected().assertHeightIsAtLeast(48.dp)
        onNodeWithText("Map").assert(hasRole(Role.Tab)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        val todayBounds = onNodeWithText("Today").fetchSemanticsNode().boundsInRoot
        val mapBounds = onNodeWithText("Map").fetchSemanticsNode().boundsInRoot
        // At density 2f, 48 dp is 96 px and 8 dp spacing is 16 px.
        kotlin.test.assertTrue(todayBounds.width >= 96f, "Today tab width ${todayBounds.width} >= 96 px (48 dp)")
        kotlin.test.assertTrue(mapBounds.width >= 96f, "Map tab width ${mapBounds.width} >= 96 px (48 dp)")
        val gapPx = mapBounds.left - todayBounds.right
        kotlin.test.assertTrue(gapPx >= 15.9f, "Bottom bar tab gap $gapPx px >= 16 px (8 dp)")
    }

    @Test
    fun aMoodStripIsOneSpokenNodeWithAnActionPerBlock() = runComposeUiTest {
        var tapped = -1
        setContent {
            MaterialTheme {
                MoodStripLayout(
                    blocks = listOf(MoodBlock(WhoStep.GUIDELINE), MoodBlock(null), MoodBlock(WhoStep.IT2), MoodBlock(WhoStep.IT1, weight = 0f)),
                    words = listOf("Fresh air", "n/a", "Poor", "Smoky"),
                    captions = listOf("7 days", "24 hours", "1 hour", "never shown"),
                    selected = null,
                    selectedSpoken = null,
                    onBlock = { tapped = it },
                    modifier = Modifier.testTag("strip"),
                )
            }
        }
        val threeActions = SemanticsMatcher("three custom actions") { SemanticsActions.CustomActions in it.config && it.config[SemanticsActions.CustomActions].count() == 3 }
        onNodeWithTag("strip").assert(hasContentDescription("Fresh air 7 days, n/a 24 hours, Poor 1 hour")).assert(threeActions)
        onNodeWithTag("strip").onChildren().assertCountEquals(0)
        onNodeWithTag("strip").fetchSemanticsNode().config[SemanticsActions.CustomActions][2].action()
        kotlin.test.assertEquals(2, tapped)
    }

    @Test
    fun aChosenMoodBlockIsAnnouncedAndTheStripIsATouchTarget() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    MoodStripLayout(
                        blocks = listOf(MoodBlock(WhoStep.GUIDELINE, weight = 2f), MoodBlock(WhoStep.IT2)),
                        words = listOf("Fresh air", "Poor"),
                        captions = listOf("2 areas", "1 area"),
                        selected = 1,
                        selectedSpoken = "Poor 1 area selected",
                        onBlock = {},
                        modifier = Modifier.testTag("tappable"),
                    )
                    MoodStripLayout(
                        blocks = listOf(MoodBlock(WhoStep.GUIDELINE)),
                        words = listOf("Fresh air"),
                        captions = emptyList(),
                        selected = null,
                        selectedSpoken = null,
                        onBlock = null,
                        modifier = Modifier.testTag("plain"),
                    )
                }
            }
        }
        onNodeWithTag("tappable").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Poor 1 area selected"))
        onNodeWithTag("tappable").onChildren().assertCountEquals(0)
        onNodeWithTag("tappable").assertHeightIsAtLeast(48.dp)
        onNodeWithTag("plain").assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        onNodeWithTag("plain").assertHeightIsEqualTo(28.dp)
    }

    @Test
    fun visibleCaptionsKeepTheStripOneNode() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    MoodStripLayout(
                        blocks = listOf(MoodBlock(WhoStep.GUIDELINE), MoodBlock(null), MoodBlock(WhoStep.IT2)),
                        words = listOf("Fresh air", "n/a", "Poor"),
                        captions = listOf("7 days", "24 hours", "Last hour"),
                        selected = null,
                        selectedSpoken = null,
                        onBlock = null,
                        modifier = Modifier.testTag("captioned"),
                    )
                }
            }
        }
        onNodeWithTag("captioned").onChildren().assertCountEquals(0)
        // The caption row sits under the 28 dp bar.
        onNodeWithTag("captioned").assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun aMoodLegendIsOneSpokenNode() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MoodLegendLayout(
                    words = listOf("Fresh air", "Fair", "Getting hazy", "Poor", "Smoky", "Thick smoke"),
                    title = "PM2.5",
                    modifier = Modifier.testTag("legend"),
                )
            }
        }
        onNodeWithTag("legend").assert(hasContentDescription("PM2.5. Fresh air, Fair, Getting hazy, Poor, Smoky, Thick smoke"))
        onNodeWithTag("legend").onChildren().assertCountEquals(0)
    }

    /** The public strip builds the selected state from its `mood_strip_selected` resource. */
    @Test
    fun aMoodStripAnnouncesTheChosenBlockFromItsResource() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MoodStrip(
                    blocks = listOf(MoodBlock(WhoStep.GUIDELINE), MoodBlock(WhoStep.IT2)),
                    captions = listOf("2 areas", "1 area"),
                    selected = 1,
                    onBlock = {},
                    modifier = Modifier.testTag("strip"),
                )
            }
        }
        onNodeWithTag("strip").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Poor 1 area selected"))
    }

    /** A narrow block's caption shrinks or wraps between words, never cutting "128 areas" inside a word. */
    @Test
    fun aLongCaptionInANarrowBlockKeepsWholeWords() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column(Modifier.width(300.dp)) {
                    MoodStripLayout(
                        blocks = listOf(MoodBlock(WhoStep.GUIDELINE, weight = 1f), MoodBlock(WhoStep.IT2, weight = 99f)),
                        words = listOf("Fresh air", "Poor"),
                        captions = listOf("128 areas", "4 areas"),
                        selected = null,
                        selectedSpoken = null,
                        onBlock = {},
                        modifier = Modifier.testTag("narrow"),
                    )
                }
            }
        }
        // 28 dp bar, 4 dp gap and at least one caption line.
        onNodeWithTag("narrow").assertHeightIsAtLeast(48.dp)
        val caption = textLayout("128 areas")
        kotlin.test.assertTrue(caption.lineCount <= FitTextLimits.LabelMaxLines, "caption lines ${caption.lineCount}")
        kotlin.test.assertFalse(breaksInsideWord(caption), "caption splits a word")
    }

    /** Inside a horizontal scroll the strip has no width to share, so each block takes a fixed width. */
    @Test
    fun anUnboundedStripStillLaysOut() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MoodStripLayout(
                        blocks = listOf(MoodBlock(WhoStep.GUIDELINE), MoodBlock(null), MoodBlock(WhoStep.IT2)),
                        words = listOf("Fresh air", "n/a", "Poor"),
                        captions = emptyList(),
                        selected = null,
                        selectedSpoken = null,
                        onBlock = null,
                        modifier = Modifier.testTag("unbounded"),
                    )
                }
            }
        }
        // Three 64 dp blocks and two 3 dp gaps.
        onNodeWithTag("unbounded").assertWidthIsEqualTo(198.dp)
    }

    @Test
    fun floatingPillAndInfoButtonsEnforce48DpTouchTargets() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    FloatingIconButton(
                        onClick = {},
                        contentDescription = "Zoom in",
                        modifier = Modifier.testTag("icon-btn"),
                    ) { Text("+") }
                    FloatingTextButton(
                        text = "Scan",
                        onClick = {},
                        modifier = Modifier.testTag("text-btn"),
                    )
                    PillButton(
                        text = "Continue",
                        onClick = {},
                        modifier = Modifier.testTag("pill-btn"),
                    )
                    OutlinePillButton(
                        text = "Cancel",
                        onClick = {},
                        modifier = Modifier.testTag("outline-btn"),
                    )
                    InfoButton(
                        info = InfoContent("Wi-Fi", "Connects over home Wi-Fi."),
                        modifier = Modifier.testTag("info-btn"),
                    )
                }
            }
        }
        for (tag in listOf("icon-btn", "text-btn", "pill-btn", "outline-btn", "info-btn")) {
            onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
            val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            kotlin.test.assertTrue(bounds.width >= 48f, "$tag width ${bounds.width}")
        }
    }

    @Test
    fun infoSheetContentExposesHeadingAndVisible48DpCloseAction() = runComposeUiTest {
        var dismissed = false
        setContent {
            MaterialTheme {
                InfoSheetContent(
                    info = InfoContent("Particulate matter", "Fine particles under 2.5 micrometres."),
                    closeLabel = "Close",
                    onDismiss = { dismissed = true },
                )
            }
        }
        onNodeWithText("Particulate matter").assert(isHeading)
        onNodeWithText("Close").assert(hasRole(Role.Button)).assertHeightIsAtLeast(48.dp)
        onNodeWithText("Close").fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke()
        kotlin.test.assertTrue(dismissed)
    }

    @Test
    fun eyebrowTickingCardHeaderAndSectionTabsFitAt200PercentFontScale() = runComposeUiTest {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density = 2f, fontScale = 2f),
            ) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        Eyebrow("PM", modifier = Modifier.testTag("eyebrow-short"))
                        Eyebrow(
                            "Automatic background sync over home Wi-Fi",
                            modifier = Modifier.testTag("eyebrow-long"),
                        )
                        TickingText(
                            text = { "1 s" },
                            modifier = Modifier.testTag("ticking-short"),
                        )
                        TickingText(
                            text = { "Updated 45 seconds ago over home Wi-Fi" },
                            modifier = Modifier.testTag("ticking-long"),
                        )
                        CardHeader(
                            title = "Automatic background sync over home Wi-Fi",
                            info = InfoContent("Wi-Fi sync", "Details"),
                            modifier = Modifier.testTag("card-header"),
                        )
                        SectionTabs(
                            labels = listOf("Today", "Historical readings", "Files"),
                            selectedIndex = 0,
                            onSelect = {},
                        )
                    }
                }
            }
        }
        // Long labels shrink to one line or wrap between words, never splitting a word or overflowing.
        for (text in listOf("AUTOMATIC BACKGROUND SYNC OVER HOME WI-FI", "Updated 45 seconds ago over home Wi-Fi")) {
            val layout = textLayout(text)
            kotlin.test.assertTrue(layout.lineCount <= FitTextLimits.LabelMaxLines, "$text lines ${layout.lineCount}")
            kotlin.test.assertFalse(layout.hasVisualOverflow, "$text overflows")
            kotlin.test.assertFalse(breaksInsideWord(layout), "$text splits a word")
        }

        val infoBounds = onNodeWithContentDescription("More about Wi-Fi sync").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(infoBounds.right <= 480f, "CardHeader info button right ${infoBounds.right} <= 480")
        kotlin.test.assertTrue(infoBounds.width >= 96f, "CardHeader info button width ${infoBounds.width} >= 96 px (48 dp)")
        kotlin.test.assertTrue(infoBounds.height >= 96f, "CardHeader info button height ${infoBounds.height} >= 96 px (48 dp)")

        val shortTabHeight = onNodeWithText("Today").fetchSemanticsNode().boundsInRoot.height
        val longTabHeight = onNodeWithText("Historical readings").fetchSemanticsNode().boundsInRoot.height
        kotlin.test.assertTrue(longTabHeight >= 96f, "SectionTabs height $longTabHeight >= 96 px (48 dp)")
        kotlin.test.assertTrue(longTabHeight >= shortTabHeight, "Long tab fits without clipping")
        kotlin.test.assertEquals(1, textLayout("Historical readings").lineCount, "a tab stays on one line")
    }

    @Test
    fun sectionCardAndStatLineAt200PercentFontScaleKeepTrailingAndValueInsideViewport() = runComposeUiTest {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density = 2f, fontScale = 2f),
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        SectionCard(
                            title = "Automatic background sync over home Wi-Fi",
                            trailing = {
                                OutlinePillButton(text = "Edit", onClick = {}, modifier = Modifier.testTag("trailing-action"))
                            },
                        ) {
                            StatLine(
                                label = "Status",
                                value = "OK",
                                modifier = Modifier.testTag("stat-line-short"),
                            )
                            StatLine(
                                label = "Last recorded reading from station",
                                value = "24 µg/m³",
                                modifier = Modifier.testTag("stat-line"),
                            )
                            StatLine(
                                label = "Connection",
                                value = "Connected over home Wi-Fi network",
                                modifier = Modifier.testTag("stat-line-long-value"),
                            )
                        }
                    }
                }
            }
        }
        val trailingBounds = onNodeWithTag("trailing-action").fetchSemanticsNode().boundsInRoot
        // In a 320 dp viewport at density 2f (640 px wide), the trailing button stays inside the card.
        kotlin.test.assertTrue(trailingBounds.right <= 640f, "trailing right ${trailingBounds.right} <= 640")
        kotlin.test.assertTrue(trailingBounds.width >= 96f, "trailing width ${trailingBounds.width} >= 96 px (48 dp)")
        onNodeWithTag("stat-line").assert(hasText("24 µg/m³"))

        // The long value wraps between words inside its 55 percent cap (176 dp, 352 px), and the
        // label beside it fits whole instead of splitting "Connection".
        val value = "Connected over home Wi-Fi network"
        val valueLayout = textLayout(value)
        kotlin.test.assertEquals(2, valueLayout.lineCount, "StatLine long value wraps to two lines")
        kotlin.test.assertFalse(breaksInsideWord(valueLayout), "value line ends ${valueLayout.getLineEnd(0)}")
        val valueWidth = onAllNodes(hasText(value), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.width
        kotlin.test.assertTrue(valueWidth <= 352f + 0.5f, "value width $valueWidth <= 55 percent cap")
        val labelLayout = textLayout("Connection")
        kotlin.test.assertFalse(breaksInsideWord(labelLayout), "label line ends ${labelLayout.getLineEnd(0)}")
    }

    @Test
    fun switchRowAndValueSemanticsExposeRolesAndSingleSpokenDescription() = runComposeUiTest {
        var toggled = false
        setContent {
            MaterialTheme {
                Column {
                    Row(
                        Modifier
                            .testTag("switch-row")
                            .switchRowSemantics(checked = toggled, onCheckedChange = { toggled = it }),
                    ) {
                        Text("Sync on Wi-Fi")
                    }
                    Row(
                        Modifier
                            .testTag("value-node")
                            .valueSemantics(label = "PM2.5", value = "12", unit = "µg/m³", state = "Fair"),
                    ) {
                        Text("12")
                    }
                }
            }
        }
        onNodeWithTag("switch-row").assert(hasRole(Role.Switch)).assertHeightIsAtLeast(48.dp)
        onNodeWithTag("switch-row").fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke()
        kotlin.test.assertTrue(toggled)
        onNodeWithTag("value-node").assert(hasContentDescription("PM2.5, 12 µg/m³, Fair"))
        onNodeWithTag("value-node").onChildren().assertCountEquals(0)
    }

    @Test
    fun systemReduceMotionNotificationUpdatesStateAndPeacockSkipsEntranceTransition() = runComposeUiTest {
        val originalReader = iosReduceMotionReader
        try {
            iosReduceMotionReader = { false }
            mainClock.autoAdvance = false
            var observedReduceMotion = false
            setContent {
                // The theme is the one place that reads the system setting.
                OpenSensorTheme(typography = Typography()) {
                    val reduce = LocalReduceMotion.current
                    observedReduceMotion = reduce
                    Column {
                        Peacock(
                            contentDescription = if (reduce) "Static peacock" else "Animated peacock",
                            expression = PeacockExpression.CALM,
                            animate = true,
                        )
                    }
                }
            }
            // When reduce motion is false, Peacock enters via AnimatedVisibility after LaunchedEffect,
            // so at frame 0 before advancing the clock the icon node is not yet visible.
            kotlin.test.assertFalse(observedReduceMotion)
            onNodeWithContentDescription("Animated peacock").assertDoesNotExist()
            mainClock.advanceTimeBy(500)
            onNodeWithContentDescription("Animated peacock").assertExists()

            // Flip the iOS system setting and post UIAccessibilityReduceMotionStatusDidChangeNotification.
            iosReduceMotionReader = { true }
            platform.Foundation.NSNotificationCenter.defaultCenter.postNotificationName(
                platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification,
                `object` = null,
            )
            mainClock.advanceTimeByFrame()
            kotlin.test.assertTrue(observedReduceMotion, "OpenSensorTheme provides the new setting when iOS posts it")
            // With reduce motion active, Peacock renders immediately at frame 0 without advancing 420 ms.
            onNodeWithContentDescription("Static peacock").assertExists()
        } finally {
            iosReduceMotionReader = originalReader
        }
    }

    @Test
    fun peacockReadsReduceMotionFromTheThemeNotTheSystem() = runComposeUiTest {
        val originalReader = iosReduceMotionReader
        try {
            iosReduceMotionReader = { false }
            mainClock.autoAdvance = false
            setContent {
                OpenSensorTheme(typography = Typography(), reduceMotion = true) {
                    Peacock(contentDescription = "Peacock", expression = PeacockExpression.CALM, animate = true)
                }
            }
            // The system says animate, the theme says reduce. The theme wins, so there is no entrance.
            onNodeWithContentDescription("Peacock").assertExists()
        } finally {
            iosReduceMotionReader = originalReader
        }
    }

    @Test
    fun tickingTextSpeaksIn10SecondStepsWhileItsTextTicksEverySecond() = runComposeUiTest {
        mainClock.autoAdvance = false
        var start: Long? = null
        setContent {
            MaterialTheme {
                TickingText(
                    text = { now -> "${now - (start ?: now.also { start = it })} ms" },
                    modifier = Modifier.testTag("age"),
                )
            }
        }
        onNodeWithTag("age").assert(hasContentDescription("0 ms"))
        // Let the wall clock move, then let the 1 second tick read it.
        platform.posix.usleep(50_000u)
        mainClock.advanceTimeBy(1_100)
        val shown = onNodeWithTag("age").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        kotlin.test.assertNotEquals("0 ms", shown, "the visible age ticked")
        onNodeWithTag("age").assert(hasContentDescription("0 ms"))
    }

    @Test
    fun aStatLineValueNeverTakesMoreThan55PercentOfTheRow() = runComposeUiTest {
        val value = List(24) { "word" }.joinToString(" ")
        setContent {
            MaterialTheme {
                Column(Modifier.width(300.dp)) {
                    StatLine(label = "Status", value = value, modifier = Modifier.testTag("row"))
                }
            }
        }
        val row = onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.width
        val valueWidth = onAllNodes(hasText(value), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.width
        kotlin.test.assertTrue(valueWidth <= row * 0.55f + 0.5f, "value $valueWidth of row $row")
        val labelWidth = onAllNodes(hasText("Status"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.width
        kotlin.test.assertTrue(labelWidth > 0f, "the label keeps its room")
    }

    @Test
    fun valueSemanticsReplacesTheTextInsteadOfMergingIt() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Row(Modifier.valueSemantics(label = "PM2.5", value = "12", unit = "µg/m³", state = "Fair")) {
                    Text("12")
                }
            }
        }
        // A merge would fold the child "12" into the node and read it after the description.
        onAllNodes(hasText("12")).assertCountEquals(0)
        onNodeWithContentDescription("PM2.5, 12 µg/m³, Fair").assertExists()
    }
}

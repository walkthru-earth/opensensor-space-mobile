package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.airquality.MoodBlock
import earth.walkthru.aqsync.airquality.WhoStep
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
    fun bottomBarTabsAreTabsWithASelectedState() = runComposeUiTest {
        setContent {
            MaterialTheme {
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
        onNodeWithText("Today").assert(hasRole(Role.Tab)).assertIsSelected()
        onNodeWithText("Map").assert(hasRole(Role.Tab)).assertIsNotSelected()
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
}

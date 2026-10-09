package earth.walkthru.opensensor.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
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
}

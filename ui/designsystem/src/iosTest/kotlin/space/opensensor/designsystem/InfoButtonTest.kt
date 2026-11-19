package space.opensensor.designsystem

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/** The explanation is hidden by default and opens from the "i" named after its topic. */
@OptIn(ExperimentalTestApi::class)
class InfoButtonTest {
    @Test
    fun bodyIsHiddenUntilTheInfoButtonIsTapped() = runComposeUiTest {
        val body = "How a phone proves it is allowed to connect."
        setContent {
            OpenSensorTheme {
                CardHeader("Bluetooth pairing", info = InfoContent("Bluetooth pairing", body))
            }
        }
        onNodeWithText(body).assertDoesNotExist()
        onNodeWithContentDescription("More about Bluetooth pairing").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText(body).assertExists()
    }
}

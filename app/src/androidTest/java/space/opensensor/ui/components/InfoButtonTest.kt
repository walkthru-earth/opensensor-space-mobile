package space.opensensor.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.OpenSensorTheme

/** The explanation is hidden by default and opens from the "i" named after its topic. */
@RunWith(AndroidJUnit4::class)
class InfoButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bodyIsHiddenUntilTheInfoButtonIsTapped() {
        val body = "How a phone proves it is allowed to connect."
        compose.setContent {
            OpenSensorTheme {
                CardHeader("Bluetooth pairing", info = InfoContent("Bluetooth pairing", body))
            }
        }
        compose.onNodeWithText(body).assertDoesNotExist()
        compose.onNodeWithContentDescription("More about Bluetooth pairing").assertIsDisplayed().performClick()
        compose.onNodeWithText(body).assertIsDisplayed()
    }
}

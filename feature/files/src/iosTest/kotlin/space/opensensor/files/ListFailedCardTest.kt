package space.opensensor.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** #119. A failed file list offers Try again, which asks the sensor for its list again. */
@OptIn(ExperimentalTestApi::class)
class ListFailedCardTest {
    @Test
    fun tryAgainAsksTheSensorToListAgain() = runComposeUiTest {
        val sent = ArrayList<DeviceFilesAction>()
        setContent {
            MaterialTheme { ListFailedCard("timeout", DetailLevel.SIMPLE, ready = true, onDeviceAction = { sent += it }) }
        }
        onNodeWithText("Could not read the file list.").assertExists()
        onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<DeviceFilesAction>(DeviceFilesAction.Refresh), sent)
    }

    @Test
    fun withoutASensorLinkThereIsNoDeadButton() = runComposeUiTest {
        setContent {
            MaterialTheme { ListFailedCard("timeout", DetailLevel.SIMPLE, ready = false, onDeviceAction = {}) }
        }
        onNodeWithText("Try again").assertDoesNotExist()
    }
}

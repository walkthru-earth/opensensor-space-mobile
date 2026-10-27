package space.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.board_temp_unsupported
import space.opensensor.today.resources.env_unsupported
import space.opensensor.today.resources.now_waiting
import kotlin.test.Test

/** #152. The Environment and Board cards wait quietly before `INFO` and say unsupported only for an unknown dictionary. */
@OptIn(ExperimentalTestApi::class)
class EnvironmentCardTest {
    private fun render(info: DeviceInfo?, expectUnsupported: Int, expectWaiting: Int) = runComposeUiTest {
        val state = TodayUiState(TodaySnapshot(info = info, live = LiveSample.parse("""{"pms":4,"pm25":9.0,"t":21.0}""")), DetailLevel.SIMPLE)
        var unsupported = ""
        var waiting = ""
        setContent {
            MaterialTheme {
                unsupported = stringResource(Res.string.env_unsupported)
                waiting = stringResource(Res.string.now_waiting)
                EnvironmentCard(state.liveSupport, state.capabilities, state.reading, receivedAtEpochMs = null, hero = true)
            }
        }
        onAllNodesWithText(unsupported).assertCountEquals(expectUnsupported)
        onAllNodesWithText(waiting).assertCountEquals(expectWaiting)
    }

    private fun renderBoard(info: DeviceInfo?, expectUnsupported: Int, expectWaiting: Int) = runComposeUiTest {
        val state = TodayUiState(TodaySnapshot(info = info, live = LiveSample.parse("""{"t":41.0}""")), DetailLevel.DETAILED)
        var unsupported = ""
        var waiting = ""
        setContent {
            MaterialTheme {
                unsupported = stringResource(Res.string.board_temp_unsupported)
                waiting = stringResource(Res.string.now_waiting)
                BoardCard(state.liveSupport, state.reading, state.capabilities, state.power, showTemperature = true, level = state.level)
            }
        }
        onAllNodesWithText(unsupported).assertCountEquals(expectUnsupported)
        onAllNodesWithText(waiting).assertCountEquals(expectWaiting)
    }

    @Test
    fun pendingInfoWaitsQuietlyAndNeverSaysUnsupported() = render(info = null, expectUnsupported = 0, expectWaiting = 1)

    @Test
    fun anUnknownDictionarySaysUnsupported() =
        render(info = DeviceInfo(proto = 3, dict = "0".repeat(64)), expectUnsupported = 1, expectWaiting = 0)

    @Test
    fun boardCardWaitsQuietlyWhileInfoIsPending() = renderBoard(info = null, expectUnsupported = 0, expectWaiting = 1)

    @Test
    fun boardCardSaysUnsupportedForAnUnknownDictionary() =
        renderBoard(info = DeviceInfo(proto = 3, dict = "0".repeat(64)), expectUnsupported = 1, expectWaiting = 0)
}

package earth.walkthru.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.opensensor.today.resources.Res
import earth.walkthru.opensensor.today.resources.env_unsupported
import earth.walkthru.opensensor.today.resources.now_waiting
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/** #152. The Environment card waits quietly before `INFO` and says unsupported only for an unknown dictionary. */
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

    @Test
    fun pendingInfoWaitsQuietlyAndNeverSaysUnsupported() = render(info = null, expectUnsupported = 0, expectWaiting = 1)

    @Test
    fun anUnknownDictionarySaysUnsupported() =
        render(info = DeviceInfo(proto = 3, dict = "0".repeat(64)), expectUnsupported = 1, expectWaiting = 0)
}

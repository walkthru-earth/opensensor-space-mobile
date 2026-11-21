package space.opensensor.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableSharedFlow
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The automatic setup open is collected by an effect that outlives a recomposition, so it must call
 * the host's newest `onAction`, not the one it saw first (#315). Rendered headless on the iOS
 * simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class NewestLambdaTest {
    @Test
    fun anAutomaticSetupOpenReachesTheNewestOnAction() = runComposeUiTest {
        val opens = MutableSharedFlow<SetupRequest>(extraBufferCapacity = 1)
        val calls = ArrayList<String>()
        var onAction by mutableStateOf<(DevicesAction) -> Unit>({ if (it is DevicesAction.FinishSetup) calls += "first" })
        setContent {
            MaterialTheme {
                MyDevicesScreen(
                    state = DevicesUiState(),
                    level = DetailLevel.SIMPLE,
                    bluetooth = RuntimeAccess(AccessKind.BLUETOOTH),
                    platform = DevicesPlatform(collectInBackground = true),
                    onAction = onAction,
                    setupOpens = opens,
                )
            }
        }
        waitForIdle()
        onAction = { if (it is DevicesAction.FinishSetup) calls += "second" }
        waitForIdle()
        opens.tryEmit(SetupRequest("AA", "Balcony", known = true, auto = true))
        waitForIdle()
        assertEquals(listOf("second"), calls)
    }
}

package space.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.today_forget_button
import space.opensensor.units.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Confirming Forget hands the platform [TodayAction.Forget] once and closes the dialog. */
@OptIn(ExperimentalTestApi::class)
class ForgetConfirmTest {
    private object Source : TodaySource {
        override val snapshot: StateFlow<TodaySnapshot> = MutableStateFlow(TodaySnapshot(name = "Kitchen"))
        override val notices = emptyFlow<TodayNotice>()
        override fun sync() = Unit
        override fun setTime() = Unit
        override fun flush() = Unit
        override fun stopSync() = Unit
        override fun reconnect() = Unit
        override fun disconnect() = Unit
    }

    @Test
    fun confirmingForgetSendsTheForgetAction() = runComposeUiTest {
        val actions = mutableListOf<TodayAction>()
        var controller: TodayController? = null
        var confirm = ""
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val c = controller ?: TodayController(scope, Source, MutableStateFlow(DetailLevel.SIMPLE)).also { controller = it }
                confirm = stringResource(Res.string.today_forget_button)
                TodayScreen(c, UnitFormatter.Metric, onAction = { actions += it })
            }
        }
        waitForIdle()
        controller!!.requestForget()
        waitForIdle()
        onNodeWithText(confirm, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf<TodayAction>(TodayAction.Forget), actions)
        assertNull(controller?.dialog?.value)
        onAllNodesWithText(confirm, useUnmergedTree = true).assertCountEquals(0)
    }
}

package space.opensensor.today

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.board_title
import space.opensensor.today.resources.flush
import space.opensensor.today.resources.particles_title
import space.opensensor.today.resources.storage_title
import space.opensensor.today.resources.today_forget_title
import kotlin.test.Test

/**
 * Card and dialog titles on Today are screen reader headings (#117), so TalkBack's headings list
 * and the VoiceOver rotor jump between them. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class TodayHeadingsTest {
    init {
        AirFamily.install()
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")

    @Test
    fun theBoardParticlesAndStorageTitlesAreHeadings() = runComposeUiTest {
        val state = TodayUiState(
            TodaySnapshot(info = waveshare, live = LiveSample.parse("""{"pms":4,"pm25":9.0,"t":31.5,"n03":120,"n05":60}""")),
            DetailLevel.DETAILED,
        )
        val status = DeviceStatus(fin = 12, buf = 3, intervalSeconds = 900)
        val titles = mutableListOf<String>()
        setContent {
            MaterialTheme {
                titles += listOf(stringResource(Res.string.board_title), stringResource(Res.string.particles_title), stringResource(Res.string.storage_title))
                Column {
                    BoardCard(state.liveSupport, state.reading, state.capabilities, state.power, showTemperature = true, level = state.level)
                    ParticlesCard(state.reading)
                    StorageCard(status, receivedAtEpochMs = null, level = state.level, enabled = true, onFlush = {})
                }
            }
        }
        // An uppercased eyebrow speaks its title as written through its content description.
        for (title in titles.distinct()) {
            onNode(hasContentDescription(title) or hasText(title), useUnmergedTree = true).assert(isHeading)
        }
    }

    @Test
    fun theFinalizeDialogTitleIsAHeading() = runComposeUiTest {
        var title = ""
        setContent {
            MaterialTheme {
                title = stringResource(Res.string.flush)
                TodayDialogs(TodayDialog.Flush, onConfirm = {}, onDismiss = {})
            }
        }
        onNode(hasText(title), useUnmergedTree = true).assert(isHeading)
    }

    @Test
    fun theForgetDialogTitleIsAHeading() = runComposeUiTest {
        var title = ""
        setContent {
            MaterialTheme {
                title = stringResource(Res.string.today_forget_title, "Kitchen")
                TodayDialogs(TodayDialog.Forget, onConfirm = {}, onDismiss = {}, name = "Kitchen")
            }
        }
        onNode(hasText(title), useUnmergedTree = true).assert(isHeading)
    }
}

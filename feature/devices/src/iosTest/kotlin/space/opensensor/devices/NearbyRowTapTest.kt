package space.opensensor.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tap on a nearby row in My devices (#262). A sensor this phone has not added opens the Add sensor
 * wizard with it picked, and an added one opens its Today. Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class NearbyRowTapTest {
    private fun row(id: String, name: String, known: Boolean) =
        DeviceRow(id, name, thing = null, rssi = -60, bonded = false, known = known, wifi = null, lastSeenMs = null, archive = null)

    private val fresh = row("AA:BB:CC:DD:EE:01", "Fresh sensor", known = false)
    private val added = row("AA:BB:CC:DD:EE:02", "Added sensor", known = true)

    @Test
    fun aNearbyRowTapAddsANewSensorAndOpensAnAddedOne() = runComposeUiTest {
        val bluetooth = RuntimeAccess(AccessKind.BLUETOOTH)
        val sent = mutableListOf<DevicesAction>()
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    MyDevicesScreen(
                        state = DevicesUiState(list = DeviceList(nearby = listOf(fresh, added), knownCount = 1)),
                        level = DetailLevel.SIMPLE,
                        bluetooth = bluetooth,
                        platform = DevicesPlatform(collectInBackground = true),
                        onAction = sent::add,
                    )
                }
            }
        }
        // Scan start and stop come from the screen's lifecycle, only the taps matter here.
        fun taps() = sent.filter { it is DevicesAction.Add || it is DevicesAction.Open }

        onNode(hasClickAction() and hasText(fresh.name!!)).performClick()
        waitForIdle()
        assertEquals(listOf<DevicesAction>(DevicesAction.Add(fresh.id, fresh.name)), taps())

        onNode(hasClickAction() and hasText(added.name!!)).performClick()
        waitForIdle()
        assertEquals(
            listOf(DevicesAction.Add(fresh.id, fresh.name), DevicesAction.Open(added.id, added.name, connect = bluetooth.ready)),
            taps(),
        )
    }
}

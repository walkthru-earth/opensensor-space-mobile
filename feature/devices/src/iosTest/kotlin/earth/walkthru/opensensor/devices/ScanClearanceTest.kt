package earth.walkthru.opensensor.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.opensensor.designsystem.MinTouchTarget
import earth.walkthru.opensensor.permissions.AccessKind
import earth.walkthru.opensensor.permissions.RuntimeAccess
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The floating Scan button never covers Collect from all and its info button once the list is
 * scrolled to its end (#146). Rendered headless on the iOS simulator at 100, 200 and 310 percent
 * font, left to right and right to left, with a short and a long list, at rest and while a scan runs
 * (the labelled Stop stays expanded).
 */
@OptIn(ExperimentalTestApi::class)
class ScanClearanceTest {
    private class Setup(val known: Int, val fontScale: Float, val direction: LayoutDirection, val scanning: Boolean) {
        override fun toString() = "$known known, font ${fontScale}x, $direction, scanning $scanning"
    }

    private val setups = listOf(2, 8).flatMap { known ->
        listOf(1f, 2f, 3.1f).flatMap { scale ->
            listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).flatMap { direction ->
                listOf(false, true).map { Setup(known, scale, direction, it) }
            }
        }
    }

    private fun state(setup: Setup): DevicesUiState {
        val rows = (1..setup.known).map {
            DeviceRow(
                id = "AA:BB:CC:DD:EE:" + it.toString().padStart(2, '0'),
                name = "AQ-$it",
                station = null,
                rssi = null,
                bonded = true,
                known = true,
                wifi = null,
                lastSeenMs = null,
                archive = null,
            )
        }
        return DevicesUiState(list = DeviceList(known = rows, knownCount = rows.size), scanning = setup.scanning)
    }

    @Test
    fun scanNeverCoversCollectFromAll() {
        for (setup in setups) check(setup)
    }

    private fun check(setup: Setup) = runComposeUiTest {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, setup.fontScale),
                LocalLayoutDirection provides setup.direction,
            ) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        MyDevicesScreen(
                            state = state(setup),
                            level = DetailLevel.SIMPLE,
                            bluetooth = RuntimeAccess(AccessKind.BLUETOOTH),
                            platform = DevicesPlatform(collectInBackground = true, explainSystemBond = false),
                            onAction = {},
                        )
                    }
                }
            }
        }
        // The progress row, the guide, the known header, the rows and Collect from all.
        onNode(hasScrollToIndexAction()).performScrollToIndex(3 + setup.known)
        waitForIdle()
        val info = onNodeWithContentDescription("More about Collect from all", substring = true).fetchSemanticsNode().boundsInRoot
        val scan = onNodeWithTag(SCAN_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        val minPx = with(density) { MinTouchTarget.toPx() }
        assertTrue(info.height >= minPx - 0.5f, "$setup: info button is ${info.height} px tall, less than $minPx")
        assertTrue(info.bottom <= scan.top, "$setup: info button ends at ${info.bottom} px, Scan starts at ${scan.top}")
    }
}

package earth.walkthru.opensensor.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
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
 * The Scan button never covers Collect from all and its info button (#146). Each setup opens My
 * devices at rest, with no scroll, in viewports from 480 to 840 dp tall, so Collect from all lands
 * at many heights near the bottom edge. Then it scrolls to the end, where the info button must keep
 * its full touch target above Scan. Rendered headless on the iOS simulator at 100, 200 and 310
 * percent font, left to right and right to left, with a short and a long list, idle and scanning.
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

    private val heights = (480..840 step 24).map { it.dp }

    /** Frames where the info button was on screen at rest, so the test cannot pass by never seeing it. */
    private var seenAtRest = 0

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
        assertTrue(seenAtRest > 0, "the info button was never on screen at rest")
    }

    private fun ComposeUiTest.info(): Rect? =
        onAllNodes(hasContentDescription("More about Collect from all", substring = true))
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun ComposeUiTest.scan(): Rect = onNode(hasTestTag(SCAN_BUTTON_TAG)).fetchSemanticsNode().boundsInRoot

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun check(setup: Setup) = runComposeUiTest {
        var height by mutableStateOf(heights.first())
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, setup.fontScale),
                LocalLayoutDirection provides setup.direction,
            ) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, height)) {
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
        // At rest. Bounds are clipped to what is on screen, so a row under Scan overlaps it.
        for (h in heights) {
            height = h
            waitForIdle()
            val info = info() ?: continue
            if (info.height <= 0f) continue
            seenAtRest++
            val scan = scan()
            assertTrue(!overlaps(info, scan), "$setup, $h at rest: info button $info is under Scan $scan")
        }
        // Scrolled to the end. The progress row, the guide, the known header, the rows and Collect from all.
        height = 640.dp
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(3 + setup.known)
        waitForIdle()
        val info = checkNotNull(info()) { "$setup: no info button at the end of the list" }
        val scan = scan()
        val minPx = with(density) { MinTouchTarget.toPx() }
        assertTrue(info.height >= minPx - 0.5f, "$setup: info button is ${info.height} px tall, less than $minPx")
        assertTrue(info.bottom <= scan.top, "$setup: info button ends at ${info.bottom} px, Scan starts at ${scan.top}")
    }
}

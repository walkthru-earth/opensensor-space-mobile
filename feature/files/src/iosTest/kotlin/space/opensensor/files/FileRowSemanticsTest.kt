package space.opensensor.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.HivePartition
import space.opensensor.data.sync.ManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file row has no tap of its own. Share, Sync and Metrics are separate 48 dp buttons, each
 * naming the file it acts on (#119). Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class FileRowSemanticsTest {
    private val name = "thing=t1/year=2026/month=09/day=08/data_1945_b41d37a48c04031e9397e28ce48c2f43_1-2-1.parquet"

    private fun entry(state: FileSyncState) = ManifestEntry(name, 1_536, HivePartition.of(name), state)

    @Test
    fun aSyncedFileOffersShareAndMetricsAsTheirOwnButtons() = runComposeUiTest {
        val taps = ArrayList<String>()
        setContent {
            MaterialTheme {
                FileRow(entry(FileSyncState.SYNCED), "21:45", showName = false, showDetail = false, canSync = true, onSync = { taps += "sync" }, onShare = { taps += "share" }, onInspect = { taps += "metrics" })
            }
        }
        assertEquals(2, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        onNodeWithContentDescription("Share 21:45").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        onNodeWithContentDescription("Metrics for 21:45").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("share", "metrics"), taps)
    }

    @Test
    fun aFileOnTheSensorOnlyOffersSyncNamedByItsFile() = runComposeUiTest {
        val taps = ArrayList<String>()
        setContent {
            MaterialTheme {
                FileRow(entry(FileSyncState.NOT_SYNCED), null, showName = true, showDetail = false, canSync = true, onSync = { taps += "sync" }, onShare = { taps += "share" }, onInspect = null)
            }
        }
        assertEquals(1, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        onNodeWithContentDescription("Sync " + name.substringAfterLast('/')).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf("sync"), taps)
    }

    @Test
    fun aRowWithNothingToDoHasNoButtons() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FileRow(entry(FileSyncState.NOT_SYNCED), "21:45", showName = false, showDetail = false, canSync = false, onSync = {}, onShare = {}, onInspect = null)
            }
        }
        assertEquals(0, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).fetchSemanticsNodes().size)
    }
}

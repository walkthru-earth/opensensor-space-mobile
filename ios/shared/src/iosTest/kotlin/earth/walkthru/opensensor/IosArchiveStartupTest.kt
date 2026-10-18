package earth.walkthru.opensensor

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The iOS process start hands History to the shared HistoryStartup (#125), whose own tests cover
 * the janitor, the feed block quota and the backfill. This checks only that the archive calls it.
 */
class IosArchiveStartupTest {
    @Test
    fun indexRepairRunsTheSharedHistoryStart() {
        IosArchive.startIndexRepair()
        assertTrue(IosArchive.historyStartup.started, "startIndexRepair starts the shared History start")
    }
}

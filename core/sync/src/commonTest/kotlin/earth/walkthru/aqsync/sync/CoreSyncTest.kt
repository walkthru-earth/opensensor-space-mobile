package earth.walkthru.aqsync.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreSyncTest {
    @Test
    fun moduleIsGated() {
        assertEquals("core-sync", CoreSync.MODULE)
    }
}

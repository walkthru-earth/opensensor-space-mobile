package earth.walkthru.aqsync.archive

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreArchiveTest {
    @Test
    fun moduleIsGated() {
        assertEquals("core-archive", CoreArchive.MODULE)
    }
}

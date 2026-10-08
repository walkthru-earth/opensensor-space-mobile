package earth.walkthru.aqsync.data.settings

import earth.walkthru.aqsync.data.sync.ClockSyncMode
import org.junit.Assert.assertEquals
import org.junit.Test
import earth.walkthru.aqsync.settings.ClockSyncMode as SharedClockSyncMode

/** The engine's and the shared settings' clock modes stay the same list, so the stored name maps both ways. */
class ClockSyncModeMappingTest {
    @Test
    fun sameNamesSameOrder() {
        assertEquals(ClockSyncMode.entries.map { it.name }, SharedClockSyncMode.entries.map { it.name })
        ClockSyncMode.entries.forEach { assertEquals(it, it.toShared().toEngine()) }
    }
}

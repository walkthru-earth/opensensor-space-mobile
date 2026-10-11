package earth.walkthru.aqsync.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeriveBackfillRequestTest {
    @Test
    fun `the backfill runs DeriveWorker only while charging`() {
        val spec = AutoSyncScheduler.deriveBackfillRequest().workSpec
        assertEquals(DeriveWorker::class.java.name, spec.workerClassName)
        assertTrue(spec.constraints.requiresCharging())
        assertFalse(spec.expedited)
        assertFalse(spec.isPeriodic)
    }
}

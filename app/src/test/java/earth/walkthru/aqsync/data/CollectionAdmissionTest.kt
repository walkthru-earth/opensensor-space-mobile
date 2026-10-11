package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.CollectionOrigin.USER_TAP
import earth.walkthru.aqsync.collection.CollectionOrigin.WORKER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionAdmissionTest {
    @Test
    fun `interactive devices block every worker until all clean up`() {
        val admission = CollectionAdmission()
        val first = requireNotNull(admission.acquire(USER_TAP))
        val second = requireNotNull(admission.acquire(USER_TAP))
        assertTrue(admission.hasInteractive)
        assertNull(admission.acquire(WORKER))
        first.close()
        first.close()
        assertNull(admission.acquire(WORKER))
        second.close()
        assertFalse(admission.hasInteractive)
        assertNotNull(admission.acquire(WORKER))
    }

    @Test
    fun `workers remain admitted independently until cleanup before an interactive run`() {
        val admission = CollectionAdmission()
        val first = requireNotNull(admission.acquire(WORKER))
        val second = requireNotNull(admission.acquire(WORKER))
        assertNull(admission.acquire(USER_TAP))
        first.close()
        assertNull(admission.acquire(USER_TAP))
        second.close()
        assertNotNull(admission.acquire(USER_TAP))
    }
}

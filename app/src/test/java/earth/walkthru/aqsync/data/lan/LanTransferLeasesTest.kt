package earth.walkthru.aqsync.data.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanTransferLeasesTest {
    @Test
    fun `radio remains held until the last owner closes, including duplicate closes`() {
        var acquired = 0
        var released = 0
        val leases = LanTransferLeases({ acquired++; true }, { released++ })
        val first = requireNotNull(leases.acquire())
        val second = requireNotNull(leases.acquire())
        assertEquals(1, acquired)
        assertEquals(2, leases.activeCount)
        first.close()
        first.close()
        assertEquals(1, leases.activeCount)
        assertEquals(0, released)
        second.close()
        assertEquals(0, leases.activeCount)
        assertEquals(1, released)
        requireNotNull(leases.acquire()).close()
        assertEquals(2, acquired)
        assertEquals(2, released)
    }

    @Test
    fun `failed resource acquisition creates no owner and can be retried`() {
        var available = false
        var released = 0
        val leases = LanTransferLeases({ available }, { released++ })
        assertNull(leases.acquire())
        assertEquals(0, leases.activeCount)
        available = true
        requireNotNull(leases.acquire()).close()
        assertEquals(1, released)
    }
}

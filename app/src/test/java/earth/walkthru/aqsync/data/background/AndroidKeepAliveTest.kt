package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.CollectionScope
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.data.lan.WifiLeases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeepAliveTest {
    private val intent = CollectionIntent(1, "dev", CollectionScope.All, CollectionOrigin.USER_TAP)

    @Test
    fun `process starts the service once and holds until the last lease closes`() {
        var starts = 0
        val keepAlive = AndroidKeepAlive({ starts++; true }, { null })
        val first = requireNotNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent))
        val second = requireNotNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent))
        assertEquals(1, starts)
        assertTrue(keepAlive.processHeld.value)
        first.close()
        first.close()
        assertTrue(keepAlive.processHeld.value)
        second.close()
        assertFalse(keepAlive.processHeld.value)
    }

    @Test
    fun `a refused start returns no lease and holds nothing`() {
        val keepAlive = AndroidKeepAlive({ false }, { null })
        assertNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent))
        assertFalse(keepAlive.processHeld.value)
    }

    @Test
    fun `wifi maps to the lan lock and a failing close does not throw`() {
        var closes = 0
        val wifi = WifiLeases { AutoCloseable { closes++; error("release failed") } }
        val keepAlive = AndroidKeepAlive({ error("no service for wifi") }, wifi)
        val lease = requireNotNull(keepAlive.acquire(KeepAliveKind.WIFI, intent))
        lease.close()
        assertEquals(1, closes)
        assertFalse(keepAlive.processHeld.value)
        assertNull(AndroidKeepAlive({ true }, { null }).acquire(KeepAliveKind.WIFI, intent))
    }
}

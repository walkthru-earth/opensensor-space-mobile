package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.CollectionScope
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.data.background.AndroidKeepAlive.ServiceLoss
import earth.walkthru.aqsync.data.lan.WifiLeases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeepAliveTest {
    private val intent = CollectionIntent(1, "dev", CollectionScope.All, CollectionOrigin.USER_TAP)

    @Test
    fun `service lease holds through all owners and duplicate close`() {
        var starts = 0
        val keepAlive = AndroidKeepAlive({ starts++; true }, { null })
        val first = requireNotNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent))
        val second = requireNotNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent.copy(id = 2)))
        assertEquals(1, starts)
        assertTrue(keepAlive.processHeld.value)
        first.close()
        first.close()
        assertTrue(keepAlive.processHeld.value)
        assertFalse(keepAlive.finishIfIdle(requireNotNull(keepAlive.generation)))
        second.close()
        assertFalse(keepAlive.processHeld.value)
        assertTrue(keepAlive.finishIfIdle(requireNotNull(keepAlive.generation)))
    }

    @Test
    fun `loss names every protected intent and old leases cannot release a newer generation`() {
        val losses = mutableListOf<Pair<Long, ServiceLoss>>()
        val keepAlive = AndroidKeepAlive({ true }, { null })
        val old = requireNotNull(keepAlive.acquireProcess(intent) { losses += intent.id to it })
        val generation = requireNotNull(keepAlive.generation)
        keepAlive.onServiceStopped(generation, ServiceLoss.TIMEOUT)
        assertEquals(listOf(1L to ServiceLoss.TIMEOUT), losses)
        val new = requireNotNull(keepAlive.acquireProcess(intent.copy(id = 2)) { losses += 2L to it })
        old.close()
        keepAlive.onServiceStopped(generation, ServiceLoss.REFUSED)
        assertTrue(keepAlive.processHeld.value)
        assertEquals(1, losses.size)
        new.close()
        assertFalse(keepAlive.processHeld.value)
    }

    @Test
    fun `refusal and destruction callbacks run outside lock and restart safely`() {
        lateinit var keepAlive: AndroidKeepAlive
        keepAlive = AndroidKeepAlive({ true }, { null })
        var observed: ServiceLoss? = null
        requireNotNull(
            keepAlive.acquireProcess(intent) { loss ->
                observed = loss
                // Reentrant acquisition models a new intent started by an outcome observer.
                assertNull(keepAlive.generation)
                requireNotNull(keepAlive.acquireProcess(intent.copy(id = 2)) {})
            }
        )
        keepAlive.onServiceStopped(requireNotNull(keepAlive.generation), ServiceLoss.DESTROYED)
        assertEquals(ServiceLoss.DESTROYED, observed)
        assertTrue(keepAlive.processHeld.value)
    }

    @Test
    fun `refused start informs request and holds nothing`() {
        var loss: ServiceLoss? = null
        val keepAlive = AndroidKeepAlive({ false }, { null })
        assertNull(keepAlive.acquireProcess(intent) { loss = it })
        assertEquals(ServiceLoss.REFUSED, loss)
        assertFalse(keepAlive.processHeld.value)
    }

    @Test
    fun `worker process protection never starts the interactive service`() {
        val keepAlive = AndroidKeepAlive({ error("worker started service") }, { null })
        assertNull(keepAlive.acquire(KeepAliveKind.PROCESS, intent.copy(origin = CollectionOrigin.WORKER)))
        assertFalse(keepAlive.processHeld.value)
    }

    @Test
    fun `wifi maps to lock and duplicate or failing close does not throw`() {
        var closes = 0
        val keepAlive = AndroidKeepAlive({ error("no service for wifi") }, WifiLeases { AutoCloseable { closes++; error("release failed") } })
        val lease = requireNotNull(keepAlive.acquire(KeepAliveKind.WIFI, intent))
        lease.close()
        lease.close()
        assertEquals(1, closes)
        assertFalse(keepAlive.processHeld.value)
        assertNull(AndroidKeepAlive({ true }, { null }).acquire(KeepAliveKind.WIFI, intent))
    }
}

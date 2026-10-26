package space.opensensor.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.background.SyncServiceOwnership.Decision

class SyncServiceOwnershipTest {
    @Test
    fun `queued timeout survives a later Stop in the same generation`() {
        val ownership = SyncServiceOwnership()
        assertEquals(Decision.ACCEPT_START, ownership.command(1, 10L, 10L, isStop = false, inForeground = false))
        assertEquals(Decision.STOP_COLLECTIONS, ownership.command(2, null, 10L, isStop = true, inForeground = true))

        assertTrue(ownership.acceptsTimeout(1))
        assertEquals("shutdown uses the latest command, including Stop", 2, ownership.lastStartId)
        assertEquals(1, ownership.generationStartId)
    }

    @Test
    fun `stale queued start advances shutdown ID without invalidating a valid timeout`() {
        val ownership = SyncServiceOwnership()
        ownership.command(1, 10L, 10L, isStop = false, inForeground = false)
        assertEquals(Decision.IGNORE, ownership.command(2, 9L, 10L, isStop = false, inForeground = true))

        assertTrue(ownership.acceptsTimeout(1))
        assertEquals(10L, ownership.generation)
        assertEquals(2, ownership.lastStartId)
    }

    @Test
    fun `another start for the same generation preserves the original timeout boundary`() {
        val ownership = SyncServiceOwnership()
        ownership.command(1, 10L, 10L, isStop = false, inForeground = false)
        assertEquals(Decision.ACCEPT_START, ownership.command(2, 10L, 10L, isStop = false, inForeground = true))

        assertTrue(ownership.acceptsTimeout(1))
        assertEquals(1, ownership.generationStartId)
        assertEquals(2, ownership.lastStartId)
    }

    @Test
    fun `adopting a newer generation rejects the older queued timeout and start`() {
        val ownership = SyncServiceOwnership()
        ownership.command(1, 10L, 10L, isStop = false, inForeground = false)
        ownership.command(3, 11L, 11L, isStop = false, inForeground = true)
        assertEquals(Decision.IGNORE, ownership.command(4, 10L, 11L, isStop = false, inForeground = true))

        assertFalse(ownership.acceptsTimeout(1))
        assertFalse(ownership.acceptsTimeout(2))
        assertTrue(ownership.acceptsTimeout(3))
        assertEquals(11L, ownership.generation)
        assertEquals(3, ownership.generationStartId)
        assertEquals(4, ownership.lastStartId)
    }

    @Test
    fun `retained Stop on a fresh idle service requires collection stop and immediate idle shutdown`() {
        val ownership = SyncServiceOwnership()
        assertEquals(Decision.STOP_COLLECTIONS, ownership.command(42, null, null, isStop = true, inForeground = false))

        assertTrue(ownership.isUnleasedIdle(inForeground = false, activeGeneration = null))
        assertEquals(42, ownership.lastStartId)
        assertEquals(-1L, ownership.generation)
        assertFalse(ownership.acceptsTimeout(42))
    }

    @Test
    fun `fresh service receives Stop before its queued start and adopts the held generation`() {
        val ownership = SyncServiceOwnership()
        assertEquals(Decision.STOP_COLLECTIONS, ownership.command(42, null, 10L, isStop = true, inForeground = false))
        assertFalse(ownership.isUnleasedIdle(inForeground = false, activeGeneration = 10L))
        assertEquals(10L, ownership.generation)
        assertTrue(ownership.acceptsTimeout(42))

        ownership.command(43, 10L, 10L, isStop = false, inForeground = false)
        assertEquals(42, ownership.generationStartId)
        assertEquals(43, ownership.lastStartId)
        assertTrue(ownership.isUnleasedIdle(inForeground = false, activeGeneration = null))
    }

    @Test
    fun `stale queued start cannot adopt or stop a different held generation`() {
        val ownership = SyncServiceOwnership()
        assertEquals(Decision.IGNORE, ownership.command(1, 9L, 10L, isStop = false, inForeground = false))
        assertEquals(-1L, ownership.generation)
        assertFalse(ownership.isUnleasedIdle(inForeground = false, activeGeneration = 10L))
        assertFalse(ownership.acceptsTimeout(1))
    }

    @Test
    fun `stale start with no foreground or lease is stopped using that command ID`() {
        val ownership = SyncServiceOwnership()
        assertEquals(Decision.STOP_IDLE, ownership.command(7, 9L, null, isStop = false, inForeground = false))
        assertEquals(7, ownership.lastStartId)
        assertFalse(ownership.isUnleasedIdle(inForeground = true, activeGeneration = null))
    }
}

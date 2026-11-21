package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Page 1 `thing.setup_ack` and `thing.setup_v` (decision 0003). */
class SetupAckTest {
    private val steps = listOf("name", "location", "wifi", "panel")
    private val v = SetupAck.version(steps)

    private fun thing(json: String) = DeviceConfig.parse(json).thing

    @Test
    fun `page 1 parses the setup record`() {
        val page1 = """{"location":{"cell":"","resolution":8},"thing":{"mobility":"fixed","setup":1,"setup_ack":5,"setup_v":$v}}"""
        val t = thing(page1)!!
        assertEquals(5, t.setupAck)
        assertEquals(v, t.setupV)
        assertEquals(true, t.needsSetup)
    }

    @Test
    fun `a page 1 without the record has no step done`() {
        val t = thing("""{"thing":{"mobility":"fixed","setup":0}}""")
        assertNull(t?.setupAck)
        assertEquals(0, SetupAck.done(t, v))
        assertEquals(0, SetupAck.done(null, v))
    }

    @Test
    fun `a matching setup_v reads its bits`() {
        val t = thing("""{"thing":{"setup_ack":5,"setup_v":$v}}""")
        assertEquals(5, SetupAck.done(t, v))
        assertTrue(SetupAck.isDone(t, v, 0))
        assertFalse(SetupAck.isDone(t, v, 1))
        assertTrue(SetupAck.isDone(t, v, 2))
    }

    @Test
    fun `a stale setup_v reads as nothing done`() {
        val reordered = SetupAck.version(listOf("location", "name", "wifi", "panel"))
        val t = thing("""{"thing":{"setup_ack":15,"setup_v":$reordered}}""")
        assertEquals(0, SetupAck.done(t, v))
        assertFalse(SetupAck.isDone(t, v, 0))
        assertEquals(0, SetupAck.done(thing("""{"thing":{"setup_ack":15}}"""), v), "no setup_v")
        assertEquals(0, SetupAck.done(thing("""{"thing":{"setup_ack":15,"setup_v":0}}"""), v), "setup_v 0")
    }

    @Test
    fun `a new bit drops the bits of a stale list`() {
        val stale = thing("""{"thing":{"setup_ack":15,"setup_v":${v xor 1}}}""")
        assertEquals(0b100, SetupAck.withStep(stale, v, 2))
        val current = thing("""{"thing":{"setup_ack":1,"setup_v":$v}}""")
        assertEquals(0b101, SetupAck.withStep(current, v, 2))
        assertFailsWith<IllegalArgumentException> { SetupAck.withStep(current, v, SetupAck.MAX_STEPS) }
    }

    @Test
    fun `the version names the ordered step ids`() {
        assertEquals(v, SetupAck.version(steps.toList()))
        assertNotEquals(v, SetupAck.version(steps.reversed()))
        assertNotEquals(v, SetupAck.version(steps + "lan"))
        assertNotEquals(SetupAck.version(listOf("ab", "c")), SetupAck.version(listOf("a", "bc")))
        listOf(emptyList(), listOf("name"), steps).forEach { assertTrue(SetupAck.version(it) in 1..SetupAck.MASK_MAX) }
    }

    @Test
    fun `the version is a fixed fingerprint`() {
        // SHA-256 of the empty string starts e3 b0 c4 42. Read big endian with the top bit cleared, that is 0x63b0c442.
        assertEquals(0x63b0c442, SetupAck.version(emptyList()))
    }

    @Test
    fun `the builder sends the version with the bits`() {
        val patch = ConfigPatch.build { setupAck(v, 0b101) }
        assertEquals("thing.setup_v=$v\nthing.setup_ack=5\n", patch.encode())
        assertFalse(patch.isSite)
        assertFailsWith<IllegalArgumentException> { ConfigPatch.build { setupAck(0, 1) } }
        assertFailsWith<IllegalArgumentException> { ConfigPatch.build { setupAck(v, -1) } }
    }
}

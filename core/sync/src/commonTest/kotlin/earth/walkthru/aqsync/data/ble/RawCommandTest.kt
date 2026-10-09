package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class RawCommandTest {
    @Test
    fun denylistIsExactlyRebootAndSetConfig() {
        assertEquals(setOf(Opcode.REBOOT, Opcode.SET_CONFIG), RawCommandPolicy.DENYLIST)
        assertEquals(RawCommandPolicy.DENYLIST, DeviceSession.RAW_COMMAND_DENYLIST)
    }

    @Test
    fun rebootAndSetConfigAreRefusedInAnySpelling() {
        for (hex in listOf("0b", "0B", " 0 b ", "0b00", "09", "09 7b 7d", "\t09\n")) {
            val c = RawCommandPolicy.check(hex)
            assertIs<RawCommandCheck.Refused>(c, hex)
            assertTrue(c.opcode == Opcode.REBOOT || c.opcode == Opcode.SET_CONFIG)
        }
        assertEquals("REBOOT", (RawCommandPolicy.check("0b") as RawCommandCheck.Refused).name)
        assertEquals("SET_CONFIG", (RawCommandPolicy.check("09") as RawCommandCheck.Refused).name)
    }

    @Test
    fun readOnlyRequestsPass() {
        val c = RawCommandPolicy.check("08")
        assertIs<RawCommandCheck.Ready>(c)
        assertContentEquals(byteArrayOf(0x08), c.bytes)
        assertIs<RawCommandCheck.Ready>(RawCommandPolicy.check("0C 00 02"))
        assertIs<RawCommandCheck.Ready>(RawCommandPolicy.check("07"))
    }

    @Test
    fun malformedHexIsRejectedBeforeAnythingIsSent() {
        for (hex in listOf("0", "abc", "zz", "0x08", "+1", "-1", "08 g0")) {
            assertEquals(RawCommandCheck.InvalidHex, RawCommandPolicy.check(hex), hex)
        }
    }

    @Test
    fun lengthIsOneTo512Bytes() {
        assertEquals(RawCommandCheck.BadLength, RawCommandPolicy.check(""))
        assertEquals(RawCommandCheck.BadLength, RawCommandPolicy.check("   "))
        assertIs<RawCommandCheck.Ready>(RawCommandPolicy.check("07".repeat(512)))
        assertEquals(RawCommandCheck.BadLength, RawCommandPolicy.check("07".repeat(513)))
    }

    @Test
    fun senderNeverCallsTheExchangeForARefusedOpcode() = runTest {
        val sender = RawCommandSender { fail("exchange must not run") }
        assertEquals(RawCommandOutcome.Refused("REBOOT"), sender.send("0b"))
        assertEquals(RawCommandOutcome.Refused("SET_CONFIG"), sender.send("09"))
        assertEquals(RawCommandOutcome.InvalidHex, sender.send("0"))
        assertEquals(RawCommandOutcome.BadLength, sender.send(""))
    }

    @Test
    fun sessionRefusesTheDenylistEvenWithoutTheUiCheck() = runTest {
        val sender = RawCommandSender { throw RawCommandRefusedException(it[0].toInt()) }
        assertEquals(RawCommandOutcome.Refused("STATUS"), sender.send("07"))
    }

    @Test
    fun tokenRepliesShowOnlyTheTypeByte() = runTest {
        val token = ByteArray(32) { 0x5A }
        val raw = byteArrayOf(FrameType.TOKEN.toByte(), 0x51, 0x1F) + token
        val sender = RawCommandSender { listOf(raw to Frame.Token(8017, token)) }
        val out = sender.send("0d")
        assertIs<RawCommandOutcome.Replies>(out)
        val reply = out.replies.single()
        assertEquals("46 <redacted>", reply.hex)
        assertEquals("TOKEN port=8017 token=<redacted>", reply.decoded)
        assertFalse(reply.hex.contains("5a"))
        assertFalse(reply.decoded.contains("5a", ignoreCase = true))
    }

    @Test
    fun configRepliesAreScrubbedAndUndecodableRepliesAreKept() = runTest {
        val json = """{"wifi":{"ssid":"x","psk":"hunter22","psk_set":true},"ble":{"pin":123456,"pin_set":true}}"""
        val sender = RawCommandSender {
            listOf(
                byteArrayOf(0x40) to Frame.Config(0, json),
                byteArrayOf(0x55, 0x01) to null,
            )
        }
        val out = sender.send("08")
        assertIs<RawCommandOutcome.Replies>(out)
        val decoded = out.replies[0].decoded
        assertFalse(decoded.contains("hunter22"))
        assertFalse(decoded.contains("123456"))
        assertTrue(decoded.contains("\"psk_set\":true"))
        assertTrue(decoded.contains("\"pin_set\":true"))
        assertEquals("5501", out.replies[1].hex)
        assertEquals("? (2 B)", out.replies[1].decoded)
    }

    @Test
    fun emptyAndFailedExchangesAreReported() = runTest {
        assertEquals(RawCommandOutcome.NoResponse, RawCommandSender { emptyList() }.send("07"))
        assertEquals(RawCommandOutcome.Failed("link lost"), RawCommandSender { throw TransportException("link lost") }.send("07"))
    }
}

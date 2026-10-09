package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameRedactionTest {
    private val token = ByteArray(32) { (0xA0 + it).toByte() }

    @Test
    fun secretFramesAreTokenSetConfigAndTheLanHandshake() {
        assertTrue(FrameRedaction.isSecret(FrameDirection.RX, byteArrayOf(FrameType.TOKEN.toByte()) + token))
        assertTrue(FrameRedaction.isSecret(FrameDirection.TX, byteArrayOf(Opcode.SET_CONFIG.toByte(), 0x7B)))
        assertTrue(FrameRedaction.isSecret(FrameDirection.TX, "AQS1".encodeToByteArray() + token))
        assertFalse(FrameRedaction.isSecret(FrameDirection.TX, byteArrayOf(Opcode.GET_TOKEN.toByte())))
        assertFalse(FrameRedaction.isSecret(FrameDirection.TX, byteArrayOf(FrameType.TOKEN.toByte())))
        assertFalse(FrameRedaction.isSecret(FrameDirection.RX, byteArrayOf(Opcode.SET_CONFIG.toByte())))
        assertFalse(FrameRedaction.isSecret(FrameDirection.RX, ByteArray(0)))
    }

    @Test
    fun handshakeIsRecordedWithoutItsToken() {
        val recorder = FrameRecorder()
        recorder.tx("AQS1".encodeToByteArray() + token)
        val rec = recorder.records.value.single()
        assertTrue(rec.redacted)
        assertEquals("41…", rec.previewHex)
        assertEquals(36, rec.length)
    }

    @Test
    fun noRecordedOrRenderedFormCarriesTheTokenBytes() {
        val raw = byteArrayOf(FrameType.TOKEN.toByte(), 0x51, 0x1F) + token
        val tokenHex = token.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val recorder = FrameRecorder()
        recorder.rx(raw)
        val shown = listOf(
            recorder.records.value.single().previewHex,
            FrameRedaction.replyHex(raw),
            FrameRedaction.describe(Frame.Token(8017, token)),
        )
        for (s in shown) {
            assertFalse(s.contains(tokenHex.substring(0, 8)), s)
            assertFalse(s.contains("a0a1"), s)
        }
    }

    @Test
    fun scrubMasksOnlyTheSecretKeys() {
        val json = """{"wifi":{"ssid":"home","psk" : "p\"w","psk_set":true},"ble":{"pin":-12,"pin_set":false},"lan":{"token":"abc"}}"""
        val out = FrameRedaction.scrubSecrets(json)
        assertEquals(
            """{"wifi":{"ssid":"home","psk" : "<redacted>","psk_set":true},"ble":{"pin":"<redacted>","pin_set":false},"lan":{"token":"<redacted>"}}""",
            out,
        )
        val clean = """{"wifi":{"ssid":"home","psk_set":true}}"""
        assertEquals(clean, FrameRedaction.scrubSecrets(clean))
    }

    @Test
    fun ringKeepsOnlyTheLastCapacityFrames() {
        val recorder = FrameRecorder(capacity = 3, monotonicMs = { 0 })
        for (i in 1..10) recorder.tx(byteArrayOf(Opcode.STATUS.toByte(), i.toByte()))
        assertEquals(listOf("0708", "0709", "070a"), recorder.records.value.map { it.previewHex })
    }

    @Test
    fun longFramesKeepOnlyThePreviewPrefix() {
        val recorder = FrameRecorder()
        recorder.rx(ByteArray(300) { if (it == 0) FrameType.STATUS.toByte() else 0x11 })
        val rec = recorder.records.value.single()
        assertEquals(RawFrameRecord.PREVIEW_BYTES * 2, rec.previewHex.length)
        assertEquals(300, rec.length)
    }

    @Test
    fun registryKeepsOneRecorderPerAddressAndDropsTheOldest() {
        val registry = FrameRecorderRegistry(maxDevices = 2)
        val a = registry.recorderFor("A")
        assertSame(a, registry.recorderFor("A"))
        val b = registry.recorderFor("B")
        registry.recorderFor("A")
        registry.recorderFor("C")
        assertEquals(2, registry.size)
        assertSame(a, registry.recorderFor("A"))
        assertNotSame(b, registry.recorderFor("B"))
    }
}

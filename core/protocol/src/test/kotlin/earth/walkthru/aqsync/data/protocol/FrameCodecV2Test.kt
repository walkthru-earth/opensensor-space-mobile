package earth.walkthru.aqsync.data.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Protocol v2 additions from docs/ble-sync-protocol.md (2026-09-17): opcodes 0x08–0x0D, frames
 * 0x40–0x49, error codes 12–15. Hex fixtures are hand-assembled from the documented little-endian
 * layouts; no v2 firmware capture exists yet.
 */
class FrameCodecV2Test {

    @Test
    fun `v2 requests encode per contract`() {
        assertEquals("08", Request.GetConfig.encode().toHex())
        assertEquals("0a", Request.WifiScan.encode().toHex())
        assertEquals("0b", Request.Reboot.encode().toHex())
        assertEquals("0d", Request.GetToken.encode().toHex())
        // LOG_TAIL max_bytes=8192 (0x2000) -> 0c 0020
        assertEquals("0c0020", Request.LogTail(8192).encode().toHex())
        assertEquals("0cffff", Request.LogTail(0xFFFF).encode().toHex())
        // SET_CONFIG body is the key=value text, one line per key, each terminated by \n
        val patch = ConfigPatch.build { wifiSsid("home"); wifiPsk("correct horse battery staple"); wifiOn(true) }
        val enc = Request.SetConfig(patch).encode()
        assertEquals(0x09, enc[0].toInt())
        assertEquals("wifi.ssid=home\nwifi.psk=correct horse battery staple\nwifi.on=1\n", enc.copyOfRange(1, enc.size).decodeToString())
    }

    @Test
    fun `LOG_TAIL rejects values outside u16`() {
        try {
            Request.LogTail(70_000)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `opcode and frame names cover v2`() {
        assertEquals("GET_CONFIG", Opcode.name(0x08))
        assertEquals("SET_CONFIG", Opcode.name(0x09))
        assertEquals("WIFI_SCAN", Opcode.name(0x0A))
        assertEquals("REBOOT", Opcode.name(0x0B))
        assertEquals("LOG_TAIL", Opcode.name(0x0C))
        assertEquals("GET_TOKEN", Opcode.name(0x0D))
        assertEquals("HELLO", FrameType.name(0x47))
        assertEquals("LIVE", FrameType.name(0x49))
        assertEquals("0x50", FrameType.name(0x50))
    }

    @Test
    fun `CONFIG decodes flags and json`() {
        val json = """{"ble":{"pair":"fixed","pin":123456,"pin_default":1,"bonds":1,"display":0}}"""
        val frame = FrameCodec.decode(("40" + "01" + json.toByteArray().toHex()).hexToBytes()) as Frame.Config
        assertTrue(frame.rebootRequired)
        assertEquals(json, frame.json)
        val cfg = DeviceConfig.parse(frame.json)
        assertEquals(PairMode.FIXED, cfg.ble?.pairMode)
        assertTrue(cfg.ble!!.isPinDefault)
        assertNull(cfg.wifi)

        val noReboot = FrameCodec.decode("4000 7b7d".hexToBytes()) as Frame.Config
        assertFalse(noReboot.rebootRequired)
        assertEquals("{}", noReboot.json)
    }

    @Test
    fun `WIFI_AP decodes signed rssi auth channel and ssid`() {
        // rssi=-58 (0xC6) auth=3 (WPA2) channel=6 ssid="home"
        val frame = FrameCodec.decode("41 c6 03 06 686f6d65".hexToBytes()) as Frame.WifiAp
        assertEquals(-58, frame.rssi)
        assertEquals(WifiAuth.WPA2, frame.wifiAuth)
        assertEquals(6, frame.channel)
        assertEquals("home", frame.ssid)
        // auth=255 "other", open network
        assertEquals(WifiAuth.OTHER, (FrameCodec.decode("41 e2 ff 0b 78".hexToBytes()) as Frame.WifiAp).wifiAuth)
        assertFalse((FrameCodec.decode("41 e2 00 0b 78".hexToBytes()) as Frame.WifiAp).wifiAuth.secured)
        // unknown but in-range code maps to OTHER rather than throwing
        assertEquals(WifiAuth.OTHER, WifiAuth.fromCode(9))
    }

    @Test
    fun `WIFI_SCAN_END REBOOTING LOG_END decode`() {
        val end = FrameCodec.decode("42 0700 00".hexToBytes()) as Frame.WifiScanEnd
        assertEquals(7, end.count)
        assertTrue(end.ok)
        val failed = FrameCodec.decode("42 0000 0f".hexToBytes()) as Frame.WifiScanEnd
        assertFalse(failed.ok)
        assertEquals(ProtocolError.WIFI_UNAVAILABLE, ProtocolError.fromCode(failed.status))

        val reboot = FrameCodec.decode("43 f401".hexToBytes()) as Frame.Rebooting
        assertEquals(500, reboot.delayMs)

        // total=70000 (0x00011170) returned=8192 (0x2000)
        val logEnd = FrameCodec.decode("45 70110100 0020".hexToBytes()) as Frame.LogEnd
        assertEquals(70_000L, logEnd.total)
        assertEquals(8192, logEnd.returned)
    }

    @Test
    fun `LOG keeps raw bytes so a split UTF-8 sequence survives concatenation`() {
        val text = "PARQUET µg/m³ ok\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val cut = bytes.indexOf(0xC2.toByte()) + 1 // split inside the 2-byte 'µ'
        val a = FrameCodec.decode(byteArrayOf(0x44) + bytes.copyOfRange(0, cut)) as Frame.Log
        val b = FrameCodec.decode(byteArrayOf(0x44) + bytes.copyOfRange(cut, bytes.size)) as Frame.Log
        assertEquals(text, (a.bytes + b.bytes).decodeToString())
    }

    @Test
    fun `TOKEN decodes port and exactly 32 bytes and never prints the token`() {
        val token = ByteArray(32) { (it * 7).toByte() }
        val raw = ("46" + "1eb9" + token.toHex()).hexToBytes() // 0xb91e = 47390
        val frame = FrameCodec.decode(raw) as Frame.Token
        assertEquals(47390, frame.port)
        assertArrayEquals(token, frame.token)
        assertFalse(frame.toString().contains(token.toHex()))
        assertTrue(frame.toString().contains("redacted"))

        try {
            FrameCodec.decode(("46" + "1eb9" + token.copyOf(31).toHex()).hexToBytes())
            fail("31-byte token must be rejected")
        } catch (_: FrameDecodeException) {
        }
    }

    @Test
    fun `HELLO STATUS LIVE decode`() {
        val info = """{"proto":2,"fw":"cores3-parquet-v5","station":"53315f5f-cb85-4d8d-b623-d56266084189","max_read":16384}"""
        val hello = FrameCodec.decode(("47" + "02" + "0004" + info.toByteArray().toHex()).hexToBytes()) as Frame.Hello
        assertEquals(2, hello.proto)
        assertEquals(1024, hello.payloadMax)
        assertEquals(16384, DeviceInfo.parse(hello.infoJson).maxRead)

        val status = FrameCodec.decode(("48" + """{"up_s":5,"utc":1}""".toByteArray().toHex()).hexToBytes()) as Frame.StatusPush
        assertEquals(5L, DeviceStatus.parse(status.json).upSeconds)
        val live = FrameCodec.decode(("49" + """{"seq":9,"pms":4,"pm25":7}""".toByteArray().toHex()).hexToBytes()) as Frame.LivePush
        assertEquals(7.0, LiveSample.parse(live.json).pm25)
    }

    @Test
    fun `v2 error codes are named`() {
        val e12 = FrameCodec.decode(("7f" + "09" + "0c" + "wifi.ssid".toByteArray().toHex()).hexToBytes()) as Frame.Error
        assertEquals(ProtocolError.INVALID_CONFIG, e12.error)
        assertEquals("SET_CONFIG failed: invalid config (wifi.ssid)", e12.describe())
        assertEquals(ProtocolError.NOT_ALLOWED_ON_LINK, (FrameCodec.decode("7f 0d 0d".hexToBytes()) as Frame.Error).error)
        assertEquals(ProtocolError.AUTH_FAILED, (FrameCodec.decode("7f 00 0e".hexToBytes()) as Frame.Error).error)
        assertEquals(ProtocolError.WIFI_UNAVAILABLE, (FrameCodec.decode("7f 0a 0f".hexToBytes()) as Frame.Error).error)
    }

    @Test
    fun `every v2 frame round-trips through encode and decode`() {
        val token = ByteArray(32) { 0x5a }
        val frames = listOf<Frame>(
            Frame.Config(1, """{"lan":{"on":1}}"""),
            Frame.WifiAp(-90, 6, 11, "café"),
            Frame.WifiScanEnd(3, 0),
            Frame.Rebooting(500),
            Frame.LogEnd(1234L, 77),
            Frame.Hello(2, 1024, """{"proto":2}"""),
            Frame.StatusPush("""{"up_s":1}"""),
            Frame.LivePush("""{"seq":1}"""),
        )
        for (f in frames) {
            assertEquals(f.toString(), f, FrameCodec.decode(FrameCodec.encode(f)))
        }
        val log = FrameCodec.decode(FrameCodec.encode(Frame.Log("abc".toByteArray()))) as Frame.Log
        assertArrayEquals("abc".toByteArray(), log.bytes)
        val tok = FrameCodec.decode(FrameCodec.encode(Frame.Token(47390, token))) as Frame.Token
        assertEquals(47390, tok.port)
        assertArrayEquals(token, tok.token)
    }

    @Test
    fun `truncated v2 frames are rejected not misread`() {
        for (hex in listOf("40", "41 c6 03", "42 07", "43 f4", "45 701101", "46 1eb9", "47 02 00")) {
            try {
                FrameCodec.decode(hex.hexToBytes())
                fail("expected FrameDecodeException for $hex")
            } catch (_: FrameDecodeException) {
            }
        }
    }
}

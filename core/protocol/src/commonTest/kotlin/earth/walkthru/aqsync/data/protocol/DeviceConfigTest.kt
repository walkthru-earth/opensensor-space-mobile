package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** CONFIG JSON and SET_CONFIG line builder (docs/ble-sync-protocol.md, "Device configuration"). */
class DeviceConfigTest {
    private val full = """{"ble":{"pair":"random","pin":123456,"pin_default":1,"bonds":1,"display":1},""" +
        """"wifi":{"on":1,"ssid":"home","psk_set":1,"state":"connected","ip":"192.168.1.42","rssi":-58,"mac":"e0..6b40"},""" +
        """"lan":{"on":1,"port":47390,"host":"os-6b40","clients":0}}"""

    @Test
    fun `full document parses`() {
        val c = DeviceConfig.parse(full)
        assertEquals(PairMode.RANDOM, c.ble?.pairMode)
        assertEquals("123456", c.ble?.pinText)
        assertTrue(c.ble!!.isPinDefault)
        assertEquals(1, c.ble?.bonds)
        assertEquals(1, c.ble?.display)
        assertTrue(c.wifi!!.isOn)
        assertEquals("home", c.wifi?.ssid)
        assertTrue(c.wifi!!.hasPsk)
        assertEquals(WifiState.CONNECTED, c.wifi?.wifiState)
        assertEquals("192.168.1.42", c.wifi?.ip)
        assertEquals(-58, c.wifi?.rssi)
        assertTrue(c.lan!!.isOn)
        assertEquals(47390, c.lan?.port)
        assertEquals("os-6b40", c.lan?.host)
        assertEquals(0, c.lan?.clients)
    }

    @Test
    fun `absent objects and keys are null and unknown keys are ignored`() {
        val c = DeviceConfig.parse("""{"ble":{"pair":"fixed","pin":42,"future":true},"extra":{"x":1}}""")
        assertEquals(PairMode.FIXED, c.ble?.pairMode)
        assertEquals("000042", c.ble?.pinText) // leading zeros restored
        assertNull(c.ble?.pinDefault)
        assertFalse(c.ble!!.isPinDefault)
        assertNull(c.wifi)
        assertNull(c.lan)
        assertNull(DeviceConfig.parse("{}").ble)
        assertNull(PairMode.fromWire("weird"))
        assertNull(WifiState.fromWire(null))
        assertNull(DeviceConfig.parse("""{"wifi":{"state":"rebooting"}}""").wifi?.wifiState)
    }

    @Test
    fun `patch emits key=value lines in order with trailing newline and dedups keys`() {
        val p = ConfigPatch.build {
            blePair(PairMode.FIXED)
            blePin("000123")
            wifiOn(false)
            wifiOn(true)
            lanOn(false)
            clearBonds()
            rotateToken()
        }
        assertEquals("ble.pair=fixed\nble.pin=000123\nwifi.on=1\nlan.on=0\nble.clear_bonds=1\nlan.rotate_token=1\n", p.encode())
        assertTrue(p.changesBle)
        assertEquals("ble.pair,ble.pin,wifi.on,lan.on,ble.clear_bonds,lan.rotate_token", p.describe())
    }

    @Test
    fun `patch never prints secret values`() {
        val p = ConfigPatch.build { wifiSsid("home"); wifiPsk("hunter2hunter2") }
        assertFalse(p.toString().contains("hunter2"))
        assertFalse(Request.SetConfig(p).toString().contains("hunter2"))
        assertFalse(p.changesBle)
        assertEquals("wifi.ssid=home\nwifi.psk=hunter2hunter2\n", p.encode())
    }

    @Test
    fun `only the passphrase and the pin mark a patch as secret`() {
        assertTrue(ConfigPatch.build { wifiPsk("<dev psk>") }.carriesSecret)
        assertTrue(ConfigPatch.build { blePair(PairMode.FIXED); blePin("000000") }.carriesSecret)
        assertFalse(ConfigPatch.build { wifiSsid("<dev ssid>"); wifiOn(true) }.carriesSecret)
        assertFalse(ConfigPatch.build { blePair(PairMode.RANDOM); lanOn(true); rotateToken(); clearBonds() }.carriesSecret)
    }

    @Test
    fun `pin must be exactly six digits`() {
        for (bad in listOf("", "12345", "1234567", "12a456", "１２３４５６", " 12345")) {
            try {
                ConfigPatch.build { blePin(bad) }
                fail("PIN '$bad' should be rejected")
            } catch (_: ConfigValidationException) {
            }
        }
        assertEquals("ble.pin=000000\n", ConfigPatch.build { blePin("000000") }.encode())
        assertEquals("ble.pin=999999\n", ConfigPatch.build { blePin("999999") }.encode())
    }

    @Test
    fun `ssid and psk limits are in bytes not chars`() {
        ConfigPatch.build { wifiSsid("a".repeat(32)) }
        try {
            ConfigPatch.build { wifiSsid("a".repeat(33)) }
            fail("33-byte SSID should be rejected")
        } catch (_: ConfigValidationException) {
        }
        try {
            ConfigPatch.build { wifiSsid("é".repeat(17)) } // 34 bytes
            fail("34-byte SSID should be rejected")
        } catch (_: ConfigValidationException) {
        }
        try {
            ConfigPatch.build { wifiSsid("") }
            fail("empty SSID should be rejected")
        } catch (_: ConfigValidationException) {
        }
        try {
            ConfigPatch.build { wifiSsid("a\nb") }
            fail("line break in SSID would corrupt the line format")
        } catch (_: ConfigValidationException) {
        }
        ConfigPatch.build { wifiPsk("") } // open network
        ConfigPatch.build { wifiPsk("p".repeat(63)) }
        try {
            ConfigPatch.build { wifiPsk("p".repeat(64)) }
            fail("64-byte PSK should be rejected")
        } catch (_: ConfigValidationException) {
        }
    }

    @Test
    fun `empty patch is detectable`() {
        val p = ConfigPatch.build { }
        assertTrue(p.isEmpty)
        assertEquals("", p.encode())
    }

    @Test
    fun `sensor identity patch and board echo agree`() {
        val patch = ConfigPatch.build { sensorIdentity("pms5003t-202604081332", true) }
        assertEquals(
            "sensor.vendor=Plantower\nsensor.model=PMS5003T\nsensor.serial=PMS5003T-202604081332\nsensor.batch_candidate=1\n",
            patch.encode(),
        )
        val config = DeviceConfig.parse("""{"sensor":{"vendor":"Plantower","model":"PMS5003T","serial":"PMS5003T-202604081332","batch_candidate":1}}""")
        assertEquals("PMS5003T-202604081332", config.sensor?.serial)
        assertEquals(1, config.sensor?.batchCandidate)
    }

    @Test
    fun locationPageAndPatchCarryOnlyCellAndDeclaredCountry() {
        val text = """{"location":{"cell":"85283083fffffff","resolution":5,"cell_resolution":5,"lat":37.79,"lon":-122.35,"country":"US"}}"""
        val location = DeviceConfig.parse(text).location!!
        assertEquals("85283083fffffff", location.cell)
        assertEquals(5, location.cellResolution)
        assertEquals(37.79, location.lat)
        assertEquals("US", location.country)
        assertEquals(listOf(Opcode.GET_CONFIG.toByte()), Request.GetConfig.encode().toList())
        assertEquals(listOf(Opcode.GET_CONFIG.toByte(), 1.toByte()), Request.GetLocation.encode().toList())
        val patch = ConfigPatch.build { location("85283083fffffff", 5, "US") }
        assertEquals("location.cell=85283083fffffff\nlocation.resolution=5\nlocation.country=US\n", patch.encode())
        assertFalse("37.79" in patch.encode())
        assertNull(DeviceConfig.parse("{}").location)
        assertEquals("location.cell=\nlocation.resolution=5\nlocation.country=\n", ConfigPatch.build { location("", 5, "") }.encode())
    }
}

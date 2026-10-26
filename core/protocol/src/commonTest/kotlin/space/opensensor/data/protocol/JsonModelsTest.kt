package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Payloads captured from the CoreS3 (`arduino-cores3-parquet-v4`) over BLE on 2026-09-16. */
class JsonModelsTest {
    private val info = """{"proto":1,"fw":"arduino-cores3-parquet-v4","schema":"cores3-telemetry-v2","cols":77,""" +
        """"dict":"20f850852413abb1165f3f7ca95b4831a0e90a34ae7c7fcb41c44b108d3513c3",""" +
        """"thing":"53315f5f-cb85-4d8d-b623-d56266084189","dev":"441bf6e26b40",""" +
        """"boot":"b41d37a48c04031e9397e28ce48c2f43","max_read":16384}"""
    private val status = """{"up_s":262,"int_s":900,"buf":25,"fin":0,"drop":0,"err":0,"miss":0,"fail":0,""" +
        """"codec":"UNCOMPRESSED","utc":0,"gen":0,"sd":1,"sd_kib":30704848,"sd_used_kib":36576,"heap":198192,"part":0}"""
    private val live = """{"seq":23,"mono":252976111,"pms":4,"pm1":14,"pm25":22,"pm10":27,"c1":14,"c25":22,"c10":27,""" +
        """"n03":2127,"n05":650,"n1":129,"n25":10,"n5":6,"n10":2,"t":42.3,"bat":16372,"pct":0,"chg":0,"vbus":5136,"als":1}"""

    @Test
    fun `info parses identities`() {
        val parsed = WireJson.decodeFromString(DeviceInfo.serializer(), info)
        assertEquals(1, parsed.proto)
        assertEquals("53315f5f-cb85-4d8d-b623-d56266084189", parsed.thing)
        assertEquals("441bf6e26b40", parsed.dev)
        assertEquals(16384, parsed.maxRead)
        assertEquals(77, parsed.cols)
    }

    @Test
    fun `status parses counters and flags`() {
        val parsed = WireJson.decodeFromString(DeviceStatus.serializer(), status)
        assertEquals(262L, parsed.upSeconds)
        assertEquals(25, parsed.buf)
        assertFalse(parsed.utcSynced)
        assertTrue(parsed.sdMounted)
        assertFalse(parsed.storageFailed)
        assertEquals(30_704_848L, parsed.sdKib)
        // v5 firmware never sends the v6 open-file keys: absent means null, never 0.
        assertNull(parsed.open)
        assertNull(parsed.openRowGroups)
    }

    /** Firmware v6 `status` example from `docs/ble-sync-protocol.md` (hand-assembled from the contract, no capture yet). */
    @Test
    fun `status v6 carries open-file rows and row groups and a 1800 s interval`() {
        val v6 = """{"up_s":12345,"int_s":1800,"buf":12,"fin":764,"drop":0,"err":0,"miss":0,""" +
            """"fail":0,"codec":"LZ4_RAW","utc":1,"gen":1,"sd":1,"sd_kib":31166976,""" +
            """"sd_used_kib":34176,"heap":180000,"part":0,"open":180,"open_rg":2}"""
        val parsed = WireJson.decodeFromString(DeviceStatus.serializer(), v6)
        assertEquals(1800, parsed.intervalSeconds)
        assertEquals(180, parsed.open)
        assertEquals(2, parsed.openRowGroups)
        assertTrue(parsed.utcSynced)
    }

    @Test
    fun `live parses every key by name with seq mono and utc as header fields`() {
        val parsed = LiveSample.parse(live)
        assertEquals(23L, parsed.seq)
        assertEquals(252976111L, parsed.mono)
        assertNull(parsed.utc)
        assertEquals(22.0, parsed["pm25"]!!, 0.0)
        assertEquals(2127.0, parsed["n03"]!!, 0.0)
        assertEquals(42.3, parsed["t"]!!, 0.0)
        assertFalse(parsed.has("seq"), "header fields are not values")
        assertFalse(parsed.overflow)
    }

    @Test
    fun `a null key carries no reading now and an absent key is not carried`() {
        val parsed = LiveSample.parse("""{"seq":1,"mono":10000000,"utc":null,"pms":1,"pm25":null,"bat":4100}""")
        assertTrue(parsed.has("pm25"))
        assertNull(parsed["pm25"])
        assertFalse(parsed.has("n03"))
        assertNull(parsed["n03"])
        assertNull(parsed.utc)
        assertEquals(4100.0, parsed["bat"]!!, 0.0)
        assertFalse(parsed.overflow)
    }

    @Test
    fun `an extra key from newer firmware does not break parsing`() {
        val parsed = LiveSample.parse("""{"seq":1,"mono":1,"pms":4,"pm25":3,"future_key":true,"nested":{"x":1},"name":"x","list":[1]}""")
        assertEquals(3.0, parsed["pm25"]!!, 0.0)
        assertEquals(4.0, parsed["pms"]!!, 0.0)
    }

    @Test
    fun `live floats with trailing zeros parse as numbers`() {
        // opensensor-space-firmware#100 prints each float with its field decimals.
        val parsed = LiveSample.parse("""{"seq":1,"mono":1,"utc":1760000000000000000,"pms":4,"pm25":5.00,"t":22.50,"rh":41.0,"imu_t":40.1,"pct":87}""")
        assertEquals(5.0, parsed["pm25"]!!, 0.0)
        assertEquals(22.5, parsed["t"]!!, 0.0)
        assertEquals(41.0, parsed["rh"]!!, 0.0)
        assertEquals(40.1, parsed["imu_t"]!!, 0.0)
        assertEquals(87.0, parsed["pct"]!!, 0.0)
        assertEquals(1760000000000000000L, parsed.utc)
        assertEquals(1L, LiveSample.parse("""{"seq":1.0}""").seq)
    }

    @Test
    fun `an overflow sample has every key null`() {
        val parsed = LiveSample.parse("""{"seq":null,"mono":null,"utc":null,"pms":null,"pm25":null,"t":null}""")
        assertTrue(parsed.overflow)
        assertNull(parsed.seq)
        assertNull(parsed["pm25"])
        assertTrue(parsed.has("pm25"))
        assertFalse(LiveSample.parse("{}").overflow)
    }

    @Test
    fun `live that is not a JSON object is refused`() {
        assertFailsWith<IllegalArgumentException> { LiveSample.parse("[1,2]") }
        assertFailsWith<IllegalArgumentException> { LiveSample.parse("null") }
    }
}

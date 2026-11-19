package space.opensensor.data.protocol

import space.opensensor.model.BundledCatalog
import space.opensensor.model.Thing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract fixtures from the bundled board templates, not phone captures or hardware verification. */
class LiveReadingTest {
    private fun thing(dict: String): Thing = assertNotNull(BundledCatalog.thing("thing", dict))

    private val cores3 = thing("f4f81218dc80d1a8e6db043197a2f7180c4f075f9e8348df30eaceef44d68370")
    private val waveshare = thing("a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")

    @Test
    fun `CoreS3 reads its board temperature from imu_t and hides the always null t and rh`() {
        val reading = LiveReading(LiveSample.parse("""{"seq":7,"pms":4,"pm25":5.5,"imu_t":41.2,"t":null,"rh":null}"""), cores3)
        assertEquals("imu_temperature_c", cores3.live.temperature?.name)
        assertNull(cores3.live.humidity)
        assertEquals(41.2, reading.value("imu_temperature_c")!!, 0.0)
        assertFalse(reading.declares("ambient_temperature_c"))
        assertNull(reading.value("ambient_temperature_c"))
        assertEquals(5.5, reading.value("pm25_atmospheric_ug_m3")!!, 0.0)
        assertEquals(4, reading.code("pms_status"))
    }

    @Test
    fun `Waveshare reads air temperature from t and humidity from rh`() {
        val reading = LiveReading(LiveSample.parse("""{"pms":4,"t":23.7,"rh":58.1,"bstate":2}"""), waveshare)
        assertEquals("ambient_temperature_c", waveshare.live.temperature?.name)
        assertEquals("relative_humidity_percent", waveshare.live.humidity?.name)
        assertEquals(23.7, reading.value("ambient_temperature_c")!!, 0.0)
        assertEquals(58.1, reading.value("relative_humidity_percent")!!, 0.0)
        assertFalse(reading.declares("imu_temperature_c"), "Waveshare has no board temperature")
    }

    @Test
    fun `a value that overflows to infinity reads as no reading`() {
        val reading = LiveReading(LiveSample.parse("""{"pms":4,"pm25":1e999,"imu_t":-1e999}"""), cores3)
        assertTrue(reading.carries("pm25_atmospheric_ug_m3"))
        assertNull(reading.value("pm25_atmospheric_ug_m3"))
        assertNull(reading.value("imu_temperature_c"))
    }

    @Test
    fun `a value is read only through the key the template declares`() {
        val sample = LiveSample.parse("""{"t":23.0,"imu_t":40.0,"future_key":9}""")
        assertNull(LiveReading(sample, waveshare).value("imu_temperature_c"))
        assertNull(LiveReading(sample, cores3).value("ambient_temperature_c"), "t is never the CoreS3 air temperature")
        assertEquals(40.0, LiveReading(sample, cores3).value("imu_temperature_c")!!, 0.0)
        assertNull(LiveReading(sample, cores3).value("future_key"))
    }

    @Test
    fun `a null key is carried with no reading now and an absent key is not carried`() {
        val reading = LiveReading(LiveSample.parse("""{"pms":1,"pm25":null}"""), waveshare)
        assertTrue(reading.carries("pm25_atmospheric_ug_m3"))
        assertNull(reading.value("pm25_atmospheric_ug_m3"))
        assertFalse(reading.carries("relative_humidity_percent"))
        assertTrue(reading.declares("relative_humidity_percent"))
        assertEquals(1, reading.code("pms_status"))
    }

    @Test
    fun `an overflow sample reads every column as no reading now`() {
        val reading = LiveReading(LiveSample.parse("""{"seq":null,"pms":null,"pm25":null,"t":null,"rh":null}"""), waveshare)
        assertTrue(reading.sample.overflow)
        assertNull(reading.code("pms_status"))
        assertNull(reading.value("pm25_atmospheric_ug_m3"))
        assertNull(reading.value("ambient_temperature_c"))
    }

    @Test
    fun `utc is read through the template LIVE key as microseconds since the epoch`() {
        // The firmware contract example. Read as nanoseconds it would land in January 1970.
        val sample = LiveSample.parse("""{"seq":15021,"mono":150210000000,"utc":1757990410000000,"pms":4}""")
        listOf(cores3, waveshare).forEach { thing ->
            val reading = LiveReading(sample, thing)
            assertTrue(reading.declares(LiveReading.EVENT_TIME_UTC_US))
            assertEquals(1757990410000000.0, reading.value(LiveReading.EVENT_TIME_UTC_US)!!, 0.0)
            assertEquals("2025-09-16T02:40:10Z", reading.utc.toString())
        }
        assertEquals("2026-11-19T06:30:00.123456Z", LiveReading(LiveSample.parse("""{"utc":1795069800123456}"""), cores3).utc.toString())
        assertEquals("1969-12-31T23:59:59.999999Z", LiveReading(LiveSample.parse("""{"utc":-1}"""), cores3).utc.toString())
    }

    @Test
    fun `an unsynchronized or fractional utc has no instant`() {
        assertNull(LiveReading(LiveSample.parse("""{"utc":null,"pms":4}"""), cores3).utc)
        assertNull(LiveReading(LiveSample.parse("""{"pms":4}"""), cores3).utc)
        assertNull(LiveReading(LiveSample.parse("""{"utc":1.5}"""), cores3).utc)
    }

    @Test
    fun `an unknown dictionary has no thing and so no LIVE reading`() {
        assertNull(BundledCatalog.thing("thing", "0".repeat(64)))
        assertNull(LiveReading.of(LiveSample.parse("""{"pm25":3}"""), null))
        assertNull(LiveReading.of(null, cores3))
    }
}

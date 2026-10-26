package space.opensensor.data.protocol

import space.opensensor.model.BundledCatalog
import space.opensensor.model.Station
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract fixtures from the bundled board templates, not phone captures or hardware verification. */
class LiveReadingTest {
    private fun station(prefix: String): Station {
        val dict = BundledCatalog.templates.first { it.schema.startsWith(prefix) }.dict
        return assertNotNull(BundledCatalog.station("station", dict))
    }

    private val cores3 = station("cores3-")
    private val waveshare = station("waveshare-")

    @Test
    fun `CoreS3 reads its board temperature from imu_t and hides the always null t and rh`() {
        val reading = LiveReading(LiveSample.parse("""{"seq":7,"pms":4,"pm25":5.5,"imu_t":41.2,"t":null,"rh":null}"""), cores3)
        assertEquals("imu_temperature_c", cores3.live.temperature?.name)
        assertNull(cores3.live.humidity)
        assertEquals(41.2, reading.value("imu_temperature_c")!!, 0.0)
        assertFalse(reading.declares("ambient_temperature_c"))
        assertNull(reading.value("ambient_temperature_c"))
        assertEquals(5.5, reading.value("pm25_atmospheric_ug_m3")!!, 0.0)
        assertTrue(reading.pmValid)
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
        assertEquals(PmsStatus.fromCode(1), reading.pmsStatus)
        assertFalse(reading.pmValid)
    }

    @Test
    fun `an overflow sample reads every column as no reading now`() {
        val reading = LiveReading(LiveSample.parse("""{"seq":null,"pms":null,"pm25":null,"t":null,"rh":null}"""), waveshare)
        assertTrue(reading.sample.overflow)
        assertNull(reading.pmsStatus)
        assertNull(reading.value("pm25_atmospheric_ug_m3"))
        assertNull(reading.value("ambient_temperature_c"))
    }

    @Test
    fun `an unknown dictionary has no station and so no LIVE reading`() {
        assertNull(BundledCatalog.station("station", "0".repeat(64)))
        assertNull(LiveReading.of(LiveSample.parse("""{"pm25":3}"""), null))
        assertNull(LiveReading.of(null, cores3))
    }
}

package space.opensensor.data.history

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Capability
import space.opensensor.model.Datastream
import space.opensensor.model.Role
import space.opensensor.model.TemplateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A board with no PM columns builds metrics, a catalog and capabilities without any air metric. */
class NonAirBoardTest {
    private val probe = BoardTemplate(
        key = TemplateKey("cd".repeat(32), desc = "ef".repeat(32)),
        id = "soil-probe-v1",
        schema = "soil-probe-v1",
        title = null,
        keywords = emptyList(),
        instruments = emptyList(),
        defaultSampleIntervalS = 60.0,
        protoMin = 1,
        primaryDatetime = UtcColumns.EVENT_NS,
        datastreams = listOf(
            Datastream(UtcColumns.EVENT_NS, "int64", Role("auxiliaryInformation")),
            Datastream("soil_temperature_c", "float", Role.PHYSICAL_MEASUREMENT, unit = "Cel", liveKey = "st"),
            Datastream("soil_moisture_percent", "float", Role.PHYSICAL_MEASUREMENT, unit = "%", liveKey = "sm"),
        ),
        liveKeys = mapOf("soil_temperature_c" to "st", "soil_moisture_percent" to "sm"),
        dictionaryHref = null,
    )

    @Test
    fun `a template without PM columns gets its own metrics and no derived one`() {
        val metrics = Metrics.of(probe)
        assertEquals(listOf("soil_temperature_c", "soil_moisture_percent"), metrics.map { it.id })
        assertTrue(metrics.none { it.derived || it.healthScale != null || it.basis != null })
    }

    @Test
    fun `the catalog builds from a template without PM columns`() {
        val catalog = Metrics.catalogOf(listOf(probe))
        assertEquals(listOf("soil_temperature_c", "soil_moisture_percent"), catalog.map { it.id })
        assertEquals("soil_temperature_c", Metrics.defaultOf(catalog)?.id)
    }

    @Test
    fun `a served non air board resolves a thing without the particles capability`() {
        val caps = DeviceCapabilities.from(DeviceInfo(proto = 3, dict = probe.dict, desc = probe.key.desc?.take(TemplateKey.DESC_PREFIX_HEX)), served = listOf(probe))
        assertNotNull(caps.thing)
        assertEquals(setOf(Capability.LIVE, Capability.CONFIG), caps.capabilities)
        val sample = LiveSample.parse("""{"st":14.5,"sm":31.0,"pms":4}""")
        assertFalse(caps.showsAirCards(sample))
        assertEquals(14.5, caps.reading(sample)?.value("soil_temperature_c")!!, 0.0)
        assertEquals("soil_temperature_c", caps.temperature?.name)
    }
}

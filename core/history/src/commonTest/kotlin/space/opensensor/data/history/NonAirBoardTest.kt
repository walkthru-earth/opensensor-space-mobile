package space.opensensor.data.history

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.showsAirCards
import space.opensensor.model.AppProfile
import space.opensensor.model.Basis
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Capability
import space.opensensor.model.Datastream
import space.opensensor.model.Derivation
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.Family
import space.opensensor.model.Role
import space.opensensor.model.RowFormula
import space.opensensor.model.TemplateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** A soil family provider with a temperature compensated view of the stored moisture, named [id]. */
    private fun soilProvider(id: String = "soil_moisture_compensated") = DerivedMetricProvider { template ->
        val moisture = template.datastream("soil_moisture_percent") ?: return@DerivedMetricProvider emptyList()
        val view = moisture.copy(name = id, liveKey = null, derived = Derivation(listOf("soil_moisture_percent", "soil_temperature_c"), "test-compensation"))
        listOf(DerivedMetric(view, label = "Soil moisture compensated", formula = RowFormula { m, _ -> m }, viewOf = "soil_moisture_percent"))
    }

    @Test
    fun `a non air view is raw or corrected on its own property`() {
        val metrics = Metrics.of(probe, providers = listOf(soilProvider()))
        val view = metrics.single { it.derived }
        val stored = metrics.single { it.id == "soil_moisture_percent" }
        assertEquals(Basis.CORRECTED, view.basis)
        assertEquals(Basis.RAW, stored.basis)
        assertEquals("soil_moisture_percent", view.property)
        assertEquals("soil_moisture_percent", stored.property)
    }

    @Test
    fun `a derived id that repeats a stored column is refused`() {
        assertFailsWith<IllegalArgumentException> { Metrics.of(probe, providers = listOf(soilProvider("soil_temperature_c"))) }
    }

    @Test
    fun `two providers deriving one id are refused`() {
        assertFailsWith<IllegalArgumentException> { Metrics.of(probe, providers = listOf(soilProvider(), soilProvider())) }
    }

    @Test
    fun `installed providers build the catalog`() {
        try {
            Metrics.install(emptyList())
            assertTrue(Metrics.catalog.none { it.derived })
            assertEquals(null, Metrics.byId(AirFamily.PM25_CORRECTED_ID))
            AirFamily.install()
            assertNotNull(Metrics.byId(AirFamily.PM25_CORRECTED_ID))
        } finally {
            AirFamily.install()
        }
    }

    @Test
    fun `the catalog builds from a template without PM columns`() {
        val catalog = Metrics.catalogOf(listOf(probe))
        assertEquals(listOf("soil_temperature_c", "soil_moisture_percent"), catalog.map { it.id })
        assertEquals("soil_temperature_c", Metrics.defaultOf(catalog)?.id)
    }

    @Test
    fun `a served non air board with a reviewed profile resolves a thing without the particles capability`() {
        val reviewed = AppProfile(dict = probe.dict, family = Family.GENERIC, primary = listOf("soil_temperature_c"))
        val caps = DeviceCapabilities.from(info, served = listOf(probe)) { reviewed.takeIf { p -> p.dict == it } }
        assertNotNull(caps.thing)
        assertEquals(setOf(Capability.LIVE, Capability.CONFIG), caps.capabilities)
        val sample = LiveSample.parse("""{"st":14.5,"sm":31.0,"pms":4}""")
        assertFalse(caps.showsAirCards(sample))
        assertFalse(AirFamily.PARTICLES in caps.capabilities)
        assertEquals(14.5, caps.reading(sample)?.value("soil_temperature_c")!!, 0.0)
        assertEquals("soil_temperature_c", caps.temperature?.name)
    }

    @Test
    fun `a served board with no reviewed profile has no live view`() {
        // ble-sync-protocol LIVE key stability, the derived profile only names raw columns.
        val caps = DeviceCapabilities.from(info.copy(proto = 1), served = listOf(probe))
        assertNull(caps.thing)
        assertEquals(emptySet(), caps.capabilities)
        assertNull(caps.reading(LiveSample.parse("""{"st":14.5,"sm":31.0}""")))
    }

    private val info get() = DeviceInfo(proto = 3, dict = probe.dict, desc = probe.key.desc?.take(TemplateKey.DESC_PREFIX_HEX))
}

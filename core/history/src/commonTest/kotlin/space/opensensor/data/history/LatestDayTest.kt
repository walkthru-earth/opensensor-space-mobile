package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Datastream
import space.opensensor.model.Family
import space.opensensor.model.Role
import space.opensensor.model.TemplateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestDayTest {
    init {
        AirFamily.install()
    }

    private fun day(startS: Long, mean: Float, valid: Int = 8_640) = TierBucket(startS, 8_640, valid, 0, mean, mean, mean, sampleIntervalMs = 10_000)

    private val corrected = AirMetrics.PM25_CORRECTED.id
    private val raw = AirMetrics.PM25.id

    @Test
    fun `the corrected day wins when it is the newest and raw shows otherwise`() {
        // Same day, the corrected view wins the tie.
        val humid = LatestDays.latest(mapOf(corrected to listOf(day(172_800, 8f)), raw to listOf(day(172_800, 11f))))
        assertEquals(corrected to 8f, humid?.let { it.metric.id to it.mean })
        // No humidity sensor, so raw.
        val cores3 = LatestDays.latest(mapOf(raw to listOf(day(172_800, 20f))))
        assertEquals(raw to 20f, cores3?.let { it.metric.id to it.mean })
        // The humidity sensor stopped, the newest day is raw only.
        val oldRh = LatestDays.latest(mapOf(corrected to listOf(day(0, 7f)), raw to listOf(day(86_400, 12f), day(0, 9f))))
        assertEquals(raw to 12f, oldRh?.let { it.metric.id to it.mean })
        assertEquals(86_400L, oldRh?.startEpochS)
        assertEquals(1f, oldRh?.coverage)
    }

    @Test
    fun `days without valid rows and other metrics never stand for the thing`() {
        assertNull(LatestDays.latest(mapOf(raw to listOf(day(86_400, 0f, valid = 0)))))
        assertNull(LatestDays.latest(emptyMap()))
        // Humidity has data, but it is not the main metric while PM2.5 has a day.
        val out = LatestDays.latest(mapOf(AirMetrics.HUMIDITY.id to listOf(day(172_800, 60f)), raw to listOf(day(86_400, 5f))))
        assertEquals(raw, out?.metric?.id)
    }

    @Test
    fun `a day whose mean is not finite never stands for the thing`() {
        assertNull(LatestDays.latest(mapOf(raw to listOf(day(86_400, Float.NaN)))))
        // The newer corrected day has no finite mean, so the older finite raw day shows.
        val out = LatestDays.latest(mapOf(corrected to listOf(day(172_800, Float.POSITIVE_INFINITY)), raw to listOf(day(86_400, 6f))))
        assertEquals(raw to 6f, out?.let { it.metric.id to it.mean })
        // The newest raw day is NaN, the day before it shows.
        val older = LatestDays.latest(mapOf(raw to listOf(day(172_800, Float.NaN), day(86_400, 4f))))
        assertEquals(86_400L to 4f, older?.let { it.startEpochS to it.mean })
        // PM2.5 has valid rows but no finite day, so it still is the main metric and nothing shows, as
        // observe picks it from the metrics with data. Humidity never stands in.
        assertNull(LatestDays.latest(mapOf(raw to listOf(day(86_400, Float.NaN)), AirMetrics.HUMIDITY.id to listOf(day(86_400, 60f)))))
    }

    @Test
    fun `a non air thing shows its primary metric with its profile display step`() {
        val metrics = Metrics.of(probe, profile)
        val out = LatestDays.latest(
            mapOf(TEMPERATURE to listOf(day(86_400, 14.5f)), MOISTURE to listOf(day(86_400, 0.314f))),
            metrics,
        ) { LatestDays.displayStepOf(it, listOf(probe)) { profile } }
        assertEquals(MOISTURE, out?.metric?.id)
        assertEquals(0.01, out?.displayStep)
        assertEquals(0.1, LatestDays.displayStepOf(metrics.first { it.id == TEMPERATURE }, listOf(probe)) { profile })
    }

    @Test
    fun `observe reads the candidates over the lookback up to the last day`() = runTest {
        val tiers = object : HistoryTiers {
            val asked = mutableListOf<Triple<String, Long, Long>>()
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> {
                asked += Triple(metricId, fromS, toS)
                return flowOf(if (metricId == raw) listOf(day(10 * 86_400L, 6f)) else emptyList())
            }
            override fun observeDays(thing: String) = flowOf(DaySpan(3, 8 * 86_400L, 10 * 86_400L))
            override fun observeMetricsWithData(thing: String) = flowOf(setOf(raw, corrected, AirMetrics.HUMIDITY.id))
        }
        val out = LatestDays.observe(tiers, "t", lookbackDays = 2).first()
        assertEquals(raw to 6f, out?.let { it.metric.id to it.mean })
        assertEquals(setOf(Triple(corrected, 9 * 86_400L, 11 * 86_400L), Triple(raw, 9 * 86_400L, 11 * 86_400L)), tiers.asked.toSet())
    }

    private companion object {
        const val TEMPERATURE = "soil_temperature_c"
        const val MOISTURE = "soil_moisture"

        val probe = BoardTemplate(
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
                Datastream(TEMPERATURE, "float", Role.PHYSICAL_MEASUREMENT, unit = "Cel", liveKey = "st"),
                Datastream(MOISTURE, "float", Role.PHYSICAL_MEASUREMENT, unit = "m3/m3", liveKey = "sm"),
            ),
            liveKeys = mapOf(TEMPERATURE to "st", MOISTURE to "sm"),
            dictionaryHref = null,
        )

        val profile = AppProfile(dict = probe.dict, family = Family.GENERIC, primary = listOf(MOISTURE), displayStep = mapOf(MOISTURE to 0.01))
    }
}

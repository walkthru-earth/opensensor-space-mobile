package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestReadingTest {
    init {
        AirFamily.install()
    }

    private fun quarter(startS: Long, mean: Float, valid: Int = 90) = TierBucket(startS, 90, valid, 0, mean, mean, mean, sampleIntervalMs = 10_000)

    private val corrected = AirMetrics.PM25_CORRECTED.id
    private val raw = AirMetrics.PM25.id

    @Test
    fun `the newest valid quarter of the main metric wins and ends its bucket`() {
        val out = LatestReadings.latest(mapOf(raw to listOf(quarter(900, 7f), quarter(1_800, 9f), quarter(0, 5f))))
        assertEquals(Triple(raw, 9f, 2_700L), out?.let { Triple(it.metric.id, it.mean, it.endEpochS) })
        // The same quarter in both, the corrected view wins the tie.
        val tie = LatestReadings.latest(mapOf(corrected to listOf(quarter(1_800, 8f)), raw to listOf(quarter(1_800, 11f))))
        assertEquals(corrected, tie?.metric?.id)
        // The humidity sensor stopped, the newest quarter is raw only.
        val later = LatestReadings.latest(mapOf(corrected to listOf(quarter(900, 8f)), raw to listOf(quarter(1_800, 11f))))
        assertEquals(raw to 11f, later?.let { it.metric.id to it.mean })
    }

    @Test
    fun `quarters without a finite valid mean and other metrics never stand for the thing`() {
        assertNull(LatestReadings.latest(emptyMap()))
        assertNull(LatestReadings.latest(mapOf(raw to listOf(quarter(900, 3f, valid = 0)))))
        // The newest quarter is NaN, the one before it shows.
        assertEquals(900L + 900L, LatestReadings.latest(mapOf(raw to listOf(quarter(1_800, Float.NaN), quarter(900, 4f))))?.endEpochS)
        // Humidity is never the main metric while PM2.5 has a reading.
        val out = LatestReadings.latest(mapOf(AirMetrics.HUMIDITY.id to listOf(quarter(9_000, 60f)), raw to listOf(quarter(900, 5f))))
        assertEquals(raw, out?.metric?.id)
    }

    @Test
    fun `observe reads the quarters of the candidates over the last two days`() = runTest {
        val tiers = object : HistoryTiers {
            val asked = mutableListOf<Triple<String, Resolution, Pair<Long, Long>>>()
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> {
                asked += Triple(metricId, tier, fromS to toS)
                return flowOf(if (metricId == raw) listOf(quarter(10 * 86_400L + 900, 6f)) else emptyList())
            }
            override fun observeDays(thing: String) = flowOf(DaySpan(3, 8 * 86_400L, 10 * 86_400L))
            override fun observeMetricsWithData(thing: String) = flowOf(setOf(raw, corrected, AirMetrics.HUMIDITY.id))
        }
        val out = LatestReadings.observe(tiers, "t").first()
        assertEquals(raw to 10 * 86_400L + 1_800, out?.let { it.metric.id to it.endEpochS })
        val window = 9 * 86_400L to 11 * 86_400L
        // Each banded mean also reads its clean companion, and the flag companion once (#91).
        val ids = listOf(corrected, raw, RowChecks.FLAGS_ID, RowChecks.cleanId(corrected), RowChecks.cleanId(raw))
        assertEquals(ids.map { Triple(it, Resolution.MIN15, window) }.toSet(), tiers.asked.toSet())
    }

    @Test
    fun `with no reading in the last two days the newest older day with one is read`() = runTest {
        val day = 86_400L
        val tiers = object : HistoryTiers {
            val asked = mutableListOf<Triple<String, Resolution, Pair<Long, Long>>>()
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> {
                asked += Triple(metricId, tier, fromS to toS)
                // PM2.5 failed its gate after day 6. Only its day 6 is valid.
                val rows = when (tier) {
                    Resolution.DAY -> listOf(quarter(4 * day, 9f), quarter(6 * day, 7f))
                    else -> listOf(quarter(6 * day + 900, 7f), quarter(9 * day, 5f, valid = 0))
                }
                return flowOf(if (metricId == raw) rows.filter { it.startEpochS in fromS until toS } else emptyList())
            }
            override fun observeDays(thing: String) = flowOf(DaySpan(7, 4 * day, 10 * day))
            override fun observeMetricsWithData(thing: String) = flowOf(setOf(raw, AirMetrics.HUMIDITY.id))
        }
        val out = LatestReadings.observe(tiers, "t").first()
        assertEquals(raw to 6 * day + 1_800, out?.let { it.metric.id to it.endEpochS })
        assertEquals(Triple(raw, Resolution.DAY, 4 * day to 9 * day), tiers.asked.first { it.second == Resolution.DAY })
        assertEquals(Triple(raw, Resolution.MIN15, 6 * day to 7 * day), tiers.asked.last { it.first == raw })
    }

    @Test
    fun `a quarter with flagged rows carries their flags and the mean of the rows that passed`() = runTest {
        val start = 10 * 86_400L + 900
        val tiers = object : HistoryTiers {
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> = flowOf(
                when (metricId) {
                    raw -> listOf(quarter(start, 20f))
                    // 9 of the 90 rows were flagged with bits 1 and 8, the other 81 average 12.
                    RowChecks.FLAGS_ID -> listOf(TierBucket(start, 90, 9, 0, 9f, 9f, 9f))
                    RowChecks.cleanId(raw) -> listOf(TierBucket(start, 90, 81, 0, 12f, 12f, 12f))
                    else -> emptyList()
                },
            )
            override fun observeDays(thing: String) = flowOf(DaySpan(1, 10 * 86_400L, 10 * 86_400L))
            override fun observeMetricsWithData(thing: String) = flowOf(setOf(raw))
        }
        val out = LatestReadings.observe(tiers, "t").first()
        assertEquals(20f, out?.mean)
        assertEquals(listOf(9, 9), listOf(out?.flaggedRows, out?.flags))
        assertEquals(12f, out?.passedMean)
        // Without flagged rows the reading passes as measured.
        assertEquals(7f, LatestReadings.latest(mapOf(raw to listOf(quarter(900, 7f))))?.passedMean)
    }

    @Test
    fun `a thing with no days has no reading`() = runTest {
        val tiers = object : HistoryTiers {
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> = flowOf(emptyList())
            override fun observeDays(thing: String) = flowOf(DaySpan())
            override fun observeMetricsWithData(thing: String) = flowOf(setOf(raw))
        }
        assertNull(LatestReadings.observe(tiers, "t").first())
    }
}

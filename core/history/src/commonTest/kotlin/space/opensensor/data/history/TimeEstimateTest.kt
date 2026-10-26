package space.opensensor.data.history

import space.opensensor.archive.ClockAnchor
import space.opensensor.data.history.parquet.ParquetFile
import kotlin.test.Test

/**
 * §4.6 on real files of one boot (`e452563e…`, 2026-09-08): two unsynced files logged before the
 * laptop set the time, then a fully-timed 90-row file (the anchor), then a 2-row timed file much later.
 * The later timed file lets us check the estimate against a real stamp of the same boot.
 */
class TimeEstimateTest {

    private fun bytes(path: String) = fixtureBytes("parquet/$path")
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"

    private fun indexedRow(resource: String, name: String): IndexedFileInfo {
        val i = ParquetIndexer.index(ParquetFile.wrap(bytes(resource)), templates = CoreS3Files)
        return IndexedFileInfo(name, i.bootId, i.purpose, true, i.rows, i.utcRows, i.firstUtcNs, i.lastUtcNs, i.firstMonoUs, i.lastMonoUs)
    }

    private val anchorFile = indexedRow("device/v1-lz4-90rows-dated.parquet", "station=$station/year=2026/month=09/day=08/data_1845_e452563e96c72ca057a48924550779b6_26-79-5.parquet")
    private val laterTimed = indexedRow("device/v1-lz4-2rows-dated.parquet", "station=$station/year=2026/month=09/day=08/data_2015_e452563e96c72ca057a48924550779b6_348-349-14.parquet")
    private val unsynced64 = indexedRow("device/v1-none-64rows-unsynced.parquet", "station=$station/unsynced/boot=e452563e96c72ca057a48924550779b6/data_unsynced_e452563e96c72ca057a48924550779b6_0-63-6.parquet")
    private val unsynced1 = indexedRow("device/v1-lz4-1row-unsynced.parquet", "station=$station/unsynced/boot=e452563e96c72ca057a48924550779b6/data_unsynced_e452563e96c72ca057a48924550779b6_64-64-7.parquet")
    private val otherBoot = indexedRow("device/v2-none-90rows-unsynced.parquet", "station=$station/unsynced/boot=16201de72e4422d744b04fddf23eec41/data_unsynced_16201de72e4422d744b04fddf23eec41_0-89-0.parquet")

    @Test
    fun `invalid monotonic values and overflowing estimates never create dates`() {
        val anchor = TimeEstimate.Anchor("boot", Long.MAX_VALUE - 1, 0, "receipt")
        assertNull(anchor.estimateNsOrNull(-1))
        assertNull(anchor.estimateNsOrNull(Long.MAX_VALUE))
        assertNull(anchor.estimateNsOrNull(1))
        val underflow = TimeEstimate.Anchor("boot", Long.MIN_VALUE + 1, 10, "receipt")
        assertNull(underflow.estimateNsOrNull(0))
        assertEquals(123_456_000L, TimeEstimate.Anchor("boot", 123_457_000L, 1, "receipt").estimateNsOrNull(0))
    }

    @Test
    fun `receipt dates raw microseconds immediately without a timed file and never another boot`() {
        val receipt = ClockAnchor(station, unsynced64.bootId!!, 1_788_912_000_123_456_000L, unsynced64.lastMonoUs!!)
        val anchors = TimeEstimate.anchors(listOf(unsynced64, otherBoot), listOf(receipt))
        val span = TimeEstimate.span(unsynced64, anchors)!!
        assertEquals(receipt.utcNs, span.second)
        assertEquals(receipt.utcNs - (receipt.monoUs - unsynced64.firstMonoUs!!) * 1000L, span.first)
        assertTrue(StationDays.dayOf(span.first) < StationDays.dayOf(span.second))
        assertNull(TimeEstimate.span(otherBoot, anchors))
        assertEquals(0L, unsynced64.utcRows)
        assertNull(unsynced64.firstUtcNs)
    }

    @Test
    fun `the earliest fully-timed file of a boot is its anchor`() {
        val anchors = TimeEstimate.anchors(listOf(laterTimed, anchorFile, unsynced64, unsynced1, otherBoot))
        val a = anchors["e452563e96c72ca057a48924550779b6"]
        assertNotNull(a)
        assertEquals(anchorFile.name, a!!.sourceFile)
        assertEquals(anchorFile.firstUtcNs, a.utcNs)
        assertEquals(anchorFile.firstMonoUs, a.monoUs)
        assertNull("a boot that never got the time has no anchor", anchors["16201de72e4422d744b04fddf23eec41"])
    }

    @Test
    fun `the estimate reproduces a real later stamp of the same boot exactly`() {
        val a = TimeEstimate.anchors(listOf(anchorFile))["e452563e96c72ca057a48924550779b6"]!!
        // 43 minutes after the anchor: the firmware's own UTC for that row equals anchor + Δmono.
        assertEquals(laterTimed.firstUtcNs, a.estimateNs(laterTimed.firstMonoUs!!))
        assertEquals(laterTimed.lastUtcNs, a.estimateNs(laterTimed.lastMonoUs!!))
        // Going back 15 min 20 s before the anchor lands at 19:29:45Z on 2026-09-08 — the boot was 46 s old.
        val span = TimeEstimate.span(unsynced64, mapOf(a.boot to a))!!
        assertEquals(1_788_895_785L, span.first / 1_000_000_000L)
        assertTrue(span.second < a.utcNs)
        // Uncertainty grows with the distance: ±50 ppm over 920 s is 46 ms → "±1 s".
        assertEquals("±1 s", TimeEstimate.uncertaintyLabel(a.uncertaintySeconds(unsynced64.firstMonoUs!!)))
        // Files with real UTC never get an estimate; boots without an anchor neither.
        assertNull(TimeEstimate.span(laterTimed, mapOf(a.boot to a)))
        assertNull(TimeEstimate.span(otherBoot, mapOf(a.boot to a)))
    }

    @Test
    fun `aggregator dates the unsynced rows with the anchor and counts them as estimated`() {
        val a = TimeEstimate.anchors(listOf(anchorFile))["e452563e96c72ca057a48924550779b6"]!!
        val agg = Aggregator(station, setOf(Resolution.HOUR, Resolution.DAY), templates = CoreS3Files)
        agg.add(ParquetFile.wrap(bytes("device/v1-none-64rows-unsynced.parquet")), a)
        agg.add(ParquetFile.wrap(bytes("device/v1-lz4-1row-unsynced.parquet")), a)
        agg.add(ParquetFile.wrap(bytes("device/v1-lz4-90rows-dated.parquet")), a) // has UTC: anchor ignored
        agg.add(ParquetFile.wrap(bytes("device/v2-none-90rows-unsynced.parquet")), a) // other boot: nothing
        val day = agg.buckets().single { it.resolution == Resolution.DAY }
        assertEquals(1_788_825_600L, day.startEpochS) // 2026-09-08T00:00Z
        assertEquals(155, day.rows)
        assertEquals(65, day.estimatedRows)
        val hour = agg.buckets().single { it.resolution == Resolution.HOUR }
        assertEquals(1_788_894_000L, hour.startEpochS) // 19:00Z: estimated 19:29–19:40 and timed 19:45–19:59 share the hour
        assertEquals(155, hour.rows)
        assertEquals(65, hour.estimatedRows)
    }
}

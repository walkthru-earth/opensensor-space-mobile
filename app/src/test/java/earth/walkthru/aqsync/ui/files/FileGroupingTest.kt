package earth.walkthru.aqsync.ui.files

import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class FileGroupingTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val boot = "b41d37a48c04031e9397e28ce48c2f43"

    private fun dated(day: Int, hhmm: String, seq: Int) =
        entry("station=$station/year=2026/month=09/day=$day/data_${hhmm}_${boot}_$seq-${seq + 1}-1.parquet")

    private fun entry(name: String) = ManifestEntry(name, 1_000, HivePartition.of(name), FileSyncState.SYNCED)

    @Test
    fun `window start is the UTC day folder plus the name's HHMM`() {
        assertEquals(Instant.parse("2026-09-17T22:45:00Z"), FileGrouping.windowStartUtc(dated(17, "2245", 1)))
        assertNull(FileGrouping.windowStartUtc(entry("station=$station/unsynced/boot=$boot/data_unsynced_${boot}_0-26-0.parquet")))
        assertNull(FileGrouping.windowStartUtc(entry("legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet")))
    }

    @Test
    fun `dated files are grouped by local day east of Greenwich`() {
        // Cairo/Riyadh style UTC+3: 21:00Z on the 17th is already the 18th locally, 20:45Z is still the 17th.
        val zone = ZoneId.of("Asia/Riyadh")
        val groups = FileGrouping.group(listOf(dated(17, "2045", 1), dated(17, "2100", 3), dated(18, "0300", 5)), zone, Locale.US)
        assertEquals(2, groups.size)
        // Newest local day first.
        assertEquals(GroupTitle.LocalDay("Fri 18 Sep 2026"), groups[0].title)
        assertEquals(GroupDetail(station = "53315f5f", utcFolders = listOf("2026-09-17", "2026-09-18")), groups[0].detail)
        assertEquals(listOf("06:00", "00:00"), groups[0].entries.map { it.localTime })
        assertEquals(GroupTitle.LocalDay("Thu 17 Sep 2026"), groups[1].title)
        assertEquals(GroupDetail(station = "53315f5f", utcFolders = listOf("2026-09-17")), groups[1].detail)
        assertEquals(listOf("23:45"), groups[1].entries.map { it.localTime })
    }

    @Test
    fun `dated files are grouped by local day west of Greenwich`() {
        val zone = ZoneId.of("America/Los_Angeles") // UTC-7 in September
        val groups = FileGrouping.group(listOf(dated(18, "0300", 1), dated(18, "1500", 3)), zone, Locale.US)
        assertEquals(listOf("Fri 18 Sep 2026", "Thu 17 Sep 2026"), groups.map { (it.title as GroupTitle.LocalDay).text })
        assertEquals("08:00", groups[0].entries.single().localTime)
        assertEquals("20:00", groups[1].entries.single().localTime)
    }

    @Test
    fun `in UTC the grouping matches the SD card folders`() {
        val groups = FileGrouping.group(listOf(dated(17, "2045", 1), dated(18, "0300", 5)), ZoneId.of("UTC"), Locale.US)
        assertEquals(listOf("Fri 18 Sep 2026", "Thu 17 Sep 2026"), groups.map { (it.title as GroupTitle.LocalDay).text })
        assertEquals("03:00", groups[0].entries.single().localTime)
    }

    @Test
    fun `other layouts keep their headers and follow the dated groups`() {
        val groups = FileGrouping.group(
            listOf(
                entry("legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet"),
                entry("benchmarks/boot=e452563e96c72ca057a48924550779b6/lz4_raw_e452563e96c72ca057a48924550779b6_0-59-1.parquet"),
                entry("station=$station/unsynced/boot=$boot/data_unsynced_${boot}_0-26-0.parquet"),
                dated(17, "0900", 1),
            ),
            ZoneId.of("UTC"),
            Locale.US,
        )
        assertEquals(
            listOf(GroupTitle.LocalDay("Thu 17 Sep 2026"), GroupTitle.Unsynced, GroupTitle.Benchmarks, GroupTitle.Legacy),
            groups.map { it.title },
        )
        assertEquals(GroupDetail(boot = "b41d37a48c04"), groups[1].detail)
        assertEquals(listOf(null), groups[1].entries.map { it.localTime })
    }
}

package space.opensensor.files

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.HivePartition
import space.opensensor.data.sync.ManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FileGroupingTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val boot = "b41d37a48c04031e9397e28ce48c2f43"

    private fun dated(day: Int, hhmm: String, seq: Int) =
        entry("station=$station/year=2026/month=09/day=$day/data_${hhmm}_${boot}_$seq-${seq + 1}-1.parquet")

    private fun entry(name: String) = ManifestEntry(name, 1_000, HivePartition.of(name), FileSyncState.SYNCED)

    private fun group(entries: List<ManifestEntry>, zone: String) = FileGrouping.group(entries.map { it to null }, TimeZone.of(zone))

    @Test
    fun windowStartIsTheUtcDayFolderPlusTheNameHhmm() {
        assertEquals(Instant.parse("2026-09-17T22:45:00Z"), FileGrouping.windowStartUtc(dated(17, "2245", 1)))
        assertNull(FileGrouping.windowStartUtc(entry("station=$station/unsynced/boot=$boot/data_unsynced_${boot}_0-26-0.parquet")))
        assertNull(FileGrouping.windowStartUtc(entry("legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet")))
    }

    @Test
    fun datedFilesAreGroupedByLocalDayEastOfGreenwich() {
        // UTC+3: 21:00Z on the 17th is already the 18th locally, 20:45Z is still the 17th.
        val groups = group(listOf(dated(17, "2045", 1), dated(17, "2100", 3), dated(18, "0300", 5)), "Asia/Riyadh")
        assertEquals(2, groups.size)
        // Newest local day first.
        assertEquals(GroupTitle.LocalDay(LocalDate(2026, 9, 18)), groups[0].title)
        assertEquals(GroupDetail(station = "53315f5f", utcFolders = listOf("2026-09-17", "2026-09-18")), groups[0].detail)
        assertEquals(listOf("06:00", "00:00"), groups[0].entries.map { it.localTime })
        assertEquals(GroupTitle.LocalDay(LocalDate(2026, 9, 17)), groups[1].title)
        assertEquals(GroupDetail(station = "53315f5f", utcFolders = listOf("2026-09-17")), groups[1].detail)
        assertEquals(listOf("23:45"), groups[1].entries.map { it.localTime })
    }

    @Test
    fun datedFilesAreGroupedByLocalDayWestOfGreenwich() {
        // UTC-7 in September.
        val groups = group(listOf(dated(18, "0300", 1), dated(18, "1500", 3)), "America/Los_Angeles")
        assertEquals(listOf(LocalDate(2026, 9, 18), LocalDate(2026, 9, 17)), groups.map { (it.title as GroupTitle.LocalDay).date })
        assertEquals("08:00", groups[0].entries.single().localTime)
        assertEquals("20:00", groups[1].entries.single().localTime)
    }

    @Test
    fun inUtcTheGroupingMatchesTheSdCardFolders() {
        val groups = group(listOf(dated(17, "2045", 1), dated(18, "0300", 5)), "UTC")
        assertEquals(listOf(LocalDate(2026, 9, 18), LocalDate(2026, 9, 17)), groups.map { (it.title as GroupTitle.LocalDay).date })
        assertEquals("03:00", groups[0].entries.single().localTime)
    }

    @Test
    fun otherLayoutsKeepTheirHeadersAndFollowTheDatedGroups() {
        val groups = group(
            listOf(
                entry("legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet"),
                entry("benchmarks/boot=e452563e96c72ca057a48924550779b6/lz4_raw_e452563e96c72ca057a48924550779b6_0-59-1.parquet"),
                entry("station=$station/unsynced/boot=$boot/data_unsynced_${boot}_0-26-0.parquet"),
                dated(17, "0900", 1),
            ),
            "UTC",
        )
        assertEquals(
            listOf(GroupTitle.LocalDay(LocalDate(2026, 9, 17)), GroupTitle.Unsynced, GroupTitle.Benchmarks, GroupTitle.Legacy),
            groups.map { it.title },
        )
        assertEquals(GroupDetail(boot = "b41d37a48c04"), groups[1].detail)
        assertEquals(listOf(null), groups[1].entries.map { it.localTime })
    }

    @Test
    fun theSamePathOnTwoStationsGetsTwoRowsInOneGroup() {
        val e = dated(17, "0900", 1)
        val groups = FileGrouping.group(listOf(e to "sensor-a", e to "sensor-b"), TimeZone.UTC)
        val keys = groups.single().entries.map { it.key }
        assertEquals(2, keys.toSet().size)
    }
}

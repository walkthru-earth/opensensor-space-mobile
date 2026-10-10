package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.SafeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPlannerTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dated = "station=$station/year=2026/month=09/day=16/data_1800_b41d37a48c04031e9397e28ce48c2f43_27-28-1.parquet"
    private val unsynced = "station=$station/unsynced/boot=b41d37a48c04031e9397e28ce48c2f43/data_unsynced_b41d37a48c04031e9397e28ce48c2f43_0-26-0.parquet"
    private val bench = "benchmarks/boot=e452563e96c72ca057a48924550779b6/lz4_raw_e452563e96c72ca057a48924550779b6_0-59-1.parquet"
    private val legacy = "legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet"

    @Test
    fun hivePartitionsAreRecognisedFromRealDeviceNames() {
        val d = HivePartition.of(dated) as HivePartition.Dated
        assertEquals("2026-09-16", d.isoDate)
        assertEquals(station, d.station)
        val u = HivePartition.of(unsynced) as HivePartition.Unsynced
        assertEquals("b41d37a48c04031e9397e28ce48c2f43", u.boot)
        assertEquals("e452563e96c72ca057a48924550779b6", (HivePartition.of(bench) as HivePartition.Benchmarks).boot)
        assertEquals(HivePartition.Legacy, HivePartition.of(legacy))
    }

    @Test
    fun singleDigitMonthAndDayArePaddedInTheIsoDate() {
        val d = HivePartition.of("station=$station/year=2026/month=3/day=7/data.parquet") as HivePartition.Dated
        assertEquals("2026-03-07", d.isoDate)
    }

    @Test
    fun safeNameMirrorsTheFirmwareWhitelist() {
        assertTrue(SafeName.isValid(dated))
        assertTrue(SafeName.isValid(legacy))
        assertFalse(SafeName.isValid("../etc/passwd.parquet"))
        assertFalse(SafeName.isValid("/absolute.parquet"))
        assertFalse(SafeName.isValid("a//b.parquet"))
        assertFalse(SafeName.isValid("Upper.parquet"))
        assertFalse(SafeName.isValid("notes.txt"))
    }

    @Test
    fun safeNameAccepts399BytesAndRejects400PerTheContract() {
        val suffix = ".parquet"
        // Segments stay within the 241-byte phone limit (#60), so only the total length is tested.
        val at399 = "a".repeat(200) + "/" + "a".repeat(198 - suffix.length) + suffix
        val at400 = "a".repeat(200) + "/" + "a".repeat(199 - suffix.length) + suffix
        assertEquals(399, at399.encodeToByteArray().size)
        assertTrue(SafeName.isValid(at399))
        assertFalse(SafeName.isValid(at400))
        assertFalse(SafeName.isValid("a".repeat(512 - suffix.length) + suffix), "the old 512 limit must not come back")
    }

    @Test
    fun planDownloadsOnlyWhatTheArchiveLacksAndOrdersDatedFilesFirst() {
        val manifest = listOf(
            Frame.File(14_080, bench),
            Frame.File(18_745, unsynced),
            Frame.File(9_427, dated),
            Frame.File(7_741, legacy),
        )
        val archived = listOf(ArchivedIdentity(station, legacy, 7_741, null))
        val plan = SyncPlanner.plan(station, manifest, archived)

        assertEquals(4, plan.entries.size)
        assertEquals(1, plan.syncedCount)
        assertEquals(3, plan.missingCount)
        assertEquals(14_080L + 18_745L + 9_427L, plan.missingBytes)
        assertEquals(dated, plan.toDownload.first().name)
        assertEquals(FileSyncState.SYNCED, plan.entries.first { it.name == legacy }.state)
    }

    @Test
    fun sizeDriftOnAnImmutableFileIsAMismatchNotAReDownload() {
        val plan = SyncPlanner.plan(
            station,
            listOf(Frame.File(9_427, dated)),
            listOf(ArchivedIdentity(station, dated, 9_000, null)),
        )
        val entry = plan.entries.single()
        assertEquals(FileSyncState.MISMATCH, entry.state)
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test
    fun partialDownloadResumesAndCountsOnlyTheRemainingBytes() {
        val plan = SyncPlanner.plan(
            station,
            listOf(Frame.File(41_986, unsynced)),
            emptyList(),
            partials = mapOf(unsynced to 16_384L),
        )
        val entry = plan.entries.single()
        assertEquals(FileSyncState.PARTIAL, entry.state)
        assertEquals(16_384L, entry.partialBytes)
        assertEquals(41_986L - 16_384L, plan.missingBytes)
    }

    @Test
    fun unsafeNamesAreNeverStored() {
        val plan = SyncPlanner.plan(station, listOf(Frame.File(10, "../x.parquet")), emptyList())
        assertEquals(FileSyncState.INVALID_NAME, plan.entries.single().state)
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test
    fun archiveIdentityIsScopedToTheStation() {
        val plan = SyncPlanner.plan(
            station,
            listOf(Frame.File(9_427, dated)),
            listOf(ArchivedIdentity("other-station", dated, 9_427, null)),
        )
        assertEquals(FileSyncState.NOT_SYNCED, plan.entries.single().state)
    }
}

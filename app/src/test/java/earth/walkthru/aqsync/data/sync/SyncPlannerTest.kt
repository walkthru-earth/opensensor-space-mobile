package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dated = "station=$station/year=2026/month=09/day=16/data_1800_b41d37a48c04031e9397e28ce48c2f43_27-28-1.parquet"
    private val unsynced = "station=$station/unsynced/boot=b41d37a48c04031e9397e28ce48c2f43/data_unsynced_b41d37a48c04031e9397e28ce48c2f43_0-26-0.parquet"
    private val bench = "benchmarks/boot=e452563e96c72ca057a48924550779b6/lz4_raw_e452563e96c72ca057a48924550779b6_0-59-1.parquet"
    private val legacy = "legacy-parquet/f1179d54856fa20c31038836e5ec177e-0-6-0.parquet"

    @Test
    fun `hive partitions are recognised from real device names`() {
        val d = HivePartition.of(dated) as HivePartition.Dated
        assertEquals("2026-09-16", d.isoDate)
        assertEquals(station, d.station)
        val u = HivePartition.of(unsynced) as HivePartition.Unsynced
        assertEquals("b41d37a48c04031e9397e28ce48c2f43", u.boot)
        assertEquals("e452563e96c72ca057a48924550779b6", (HivePartition.of(bench) as HivePartition.Benchmarks).boot)
        assertEquals(HivePartition.Legacy, HivePartition.of(legacy))
    }

    @Test
    fun `safe name mirrors the firmware whitelist`() {
        assertTrue(SafeName.isValid(dated))
        assertTrue(SafeName.isValid(legacy))
        assertFalse(SafeName.isValid("../etc/passwd.parquet"))
        assertFalse(SafeName.isValid("/absolute.parquet"))
        assertFalse(SafeName.isValid("a//b.parquet"))
        assertFalse(SafeName.isValid("Upper.parquet"))
        assertFalse(SafeName.isValid("notes.txt"))
    }

    @Test
    fun `plan downloads only what the archive lacks and orders dated files first`() {
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
    fun `size drift on an immutable file is a mismatch not a re-download`() {
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
    fun `partial download resumes and counts only the remaining bytes`() {
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
    fun `unsafe names are never stored`() {
        val plan = SyncPlanner.plan(station, listOf(Frame.File(10, "../x.parquet")), emptyList())
        assertEquals(FileSyncState.INVALID_NAME, plan.entries.single().state)
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test
    fun `archive identity is scoped to the station`() {
        val plan = SyncPlanner.plan(
            station,
            listOf(Frame.File(9_427, dated)),
            listOf(ArchivedIdentity("other-station", dated, 9_427, null)),
        )
        assertEquals(FileSyncState.NOT_SYNCED, plan.entries.single().state)
    }
}

package earth.walkthru.aqsync.ui.files

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveOwnerTest {
    private fun archived(station: String, name: String) = ArchivedFile(station = station, name = name, bytes = 100, crc32 = 1, sha256 = "verified", syncedAtEpochMs = 0, sourceDevice = station)

    @Test
    fun `same legacy path on two sensors resolves only the requested storage owner`() {
        val name = "legacy-parquet/data.parquet"
        val files = listOf(archived("sensor-a", name), archived("sensor-b", name))
        assertEquals("sensor-b", archivedFileForOwner(files, name, "sensor-b")?.station)
    }

    @Test
    fun `retained Hive producer station does not replace archive owner after reset`() {
        val name = "station=old-station/year=2026/month=09/day=29/data_1200.parquet"
        val files = listOf(archived("old-station", name), archived("new-station", name))
        assertEquals("new-station", archivedFileForOwner(files, name, "new-station")?.station)
    }

    @Test
    fun `missing owner or missing file never falls back to a different sensor`() {
        val files = listOf(archived("sensor-a", "benchmarks/data.parquet"))
        assertNull(archivedFileForOwner(files, "benchmarks/data.parquet", null))
        assertNull(archivedFileForOwner(files, "benchmarks/data.parquet", "sensor-b"))
    }
}

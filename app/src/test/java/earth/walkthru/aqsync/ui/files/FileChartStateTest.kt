package earth.walkthru.aqsync.ui.files

import earth.walkthru.aqsync.data.history.ArchiveQueryResult
import earth.walkthru.aqsync.data.history.FileSummary
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChartStateTest {
    private fun file(station: String, name: String) = ArchivedFile(
        station = station,
        name = name,
        bytes = 100,
        crc32 = 1,
        sha256 = "verified",
        syncedAtEpochMs = 0,
        sourceDevice = station,
    )

    private fun summary(name: String) = FileSummary(name, 1, 1, 0, 0L, 0L, emptyMap())

    @Test
    fun `old station results cannot appear while the new station is loading`() {
        val first = FileSummaryRequest("station-a", listOf(file("station-a", "one.parquet")))
        val second = FileSummaryRequest("station-b", listOf(file("station-b", "two.parquet")))
        val oldResult = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(first, listOf(summary("one.parquet")))

        val loading = fileChartState(Metrics.primary, null, second, oldResult)
        assertTrue(loading.loading)
        assertTrue(loading.page.isEmpty())
        assertEquals(0, loading.total)

        val loaded = fileChartState(Metrics.primary, null, second, ArchiveQueryResult(second, listOf(summary("two.parquet"))))
        assertFalse(loaded.loading)
        assertEquals("two.parquet", loaded.page.single().name)
    }

    @Test
    fun `archive refresh hides the previous file list until its result arrives`() {
        val old = FileSummaryRequest("station-a", listOf(file("station-a", "one.parquet")))
        val current = FileSummaryRequest("station-a", old.files + file("station-a", "two.parquet"))
        val oldResult = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(old, listOf(summary("one.parquet")))

        val loading = fileChartState(Metrics.primary, 80, current, oldResult)
        assertTrue(loading.loading)
        assertTrue(loading.page.isEmpty())

        val loaded = fileChartState(Metrics.primary, 80, current, ArchiveQueryResult(current, listOf(summary("one.parquet"), summary("two.parquet"))))
        assertFalse(loaded.loading)
        assertEquals(0, loaded.offset)
        assertEquals(2, loaded.page.size)
    }
}

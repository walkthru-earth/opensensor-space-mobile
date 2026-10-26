package space.opensensor.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.history.ArchiveQueryResult
import space.opensensor.data.history.FileSummary
import space.opensensor.data.history.MetricAgg
import space.opensensor.data.history.Metrics
import space.opensensor.data.local.database.ArchivedFile

class FileChartStateTest {
    private fun file(thing: String, name: String) = ArchivedFile(
        thing = thing,
        name = name,
        bytes = 100,
        crc32 = 1,
        sha256 = "verified",
        syncedAtEpochMs = 0,
        sourceDevice = thing,
    )

    private fun summary(name: String) = FileSummary(name, 1, 1, 0, 0L, 0L, emptyMap())

    @Test
    fun `old thing results cannot appear while the new thing is loading`() {
        val first = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val second = FileSummaryRequest("thing-b", listOf(file("thing-b", "two.parquet")))
        val oldResult = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(first, listOf(summary("one.parquet")))

        val loading = fileChartState(Metrics.PM25, true, null, second, oldResult)
        assertTrue(loading.loading)
        assertTrue(loading.page.isEmpty())
        assertEquals(0, loading.total)

        val loaded = fileChartState(Metrics.PM25, true, null, second, ArchiveQueryResult(second, listOf(summary("two.parquet"))))
        assertFalse(loaded.loading)
        assertEquals("two.parquet", loaded.page.single().name)
    }

    @Test
    fun `archive refresh hides the previous file list until its result arrives`() {
        val old = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val current = FileSummaryRequest("thing-a", old.files + file("thing-a", "two.parquet"))
        val oldResult = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(old, listOf(summary("one.parquet")))

        val loading = fileChartState(Metrics.PM25, true, 80, current, oldResult)
        assertTrue(loading.loading)
        assertTrue(loading.page.isEmpty())

        val loaded = fileChartState(Metrics.PM25, true, 80, current, ArchiveQueryResult(current, listOf(summary("one.parquet"), summary("two.parquet"))))
        assertFalse(loaded.loading)
        assertEquals(0, loaded.offset)
        assertEquals(2, loaded.page.size)
    }

    private fun humid(name: String) = FileSummary(name, 2, 2, 0, 0L, 10L, mapOf(Metrics.PM25.id to MetricAgg(2, 20f, 10f, 30f), Metrics.PM25_CORRECTED.id to MetricAgg(2, 12f, 6f, 18f)))

    private fun dry(name: String) = FileSummary(name, 2, 2, 0, 0L, 10L, mapOf(Metrics.PM25.id to MetricAgg(2, 20f, 10f, 30f)))

    @Test
    fun `the chart opens on corrected PM2_5 and Simple never offers raw beside it`() {
        // #92. Corrected is the default at every level. Raw is a chip only in Detailed.
        val request = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val result = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(request, listOf(humid("one.parquet"), dry("two.parquet")))
        val simple = fileChartState(null, false, null, request, result)
        assertEquals(Metrics.PM25_CORRECTED, simple.metric)
        assertFalse(Metrics.PM25 in simple.metrics)
        val detailed = fileChartState(null, true, null, request, result)
        assertEquals(Metrics.PM25_CORRECTED, detailed.metric)
        assertEquals(listOf(Metrics.PM25_CORRECTED, Metrics.PM25), detailed.metrics.take(2))
        assertEquals(Metrics.PM25, fileChartState(Metrics.PM25, true, null, request, result).metric)
        // Raw picked in Detailed, then the level goes back to Simple. The chart returns to corrected.
        assertEquals(Metrics.PM25_CORRECTED, fileChartState(Metrics.PM25, false, null, request, result).metric)
    }

    @Test
    fun `files without humidity open on raw PM2_5 and offer no corrected chip`() {
        // A CoreS3, or a board whose RH column is all null. Raw is all there is, labelled raw, at every level.
        val request = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val result = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(request, listOf(dry("one.parquet")))
        for (detailed in listOf(false, true)) {
            val state = fileChartState(null, detailed, null, request, result)
            assertEquals(Metrics.PM25, state.metric)
            assertFalse(Metrics.PM25_CORRECTED in state.metrics)
            assertEquals(Metrics.PM25, state.metrics.first())
        }
    }

    @Test
    fun `while the summaries load Simple opens on corrected PM2_5 never raw`() {
        // #136. No summary is read yet, so nothing says the files lack humidity. Simple keeps corrected.
        val request = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val pending = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(request, null)
        val simple = fileChartState(null, false, null, request, pending)
        assertTrue(simple.loading)
        assertEquals(Metrics.PM25_CORRECTED, simple.metric)
        assertFalse(Metrics.PM25 in simple.metrics)
    }

    @Test
    fun `Simple offers only the profile primary metrics and Detailed every metric`() {
        val request = FileSummaryRequest("thing-a", listOf(file("thing-a", "one.parquet")))
        val result = ArchiveQueryResult<FileSummaryRequest?, List<FileSummary>>(request, listOf(humid("one.parquet")))
        val simple = fileChartState(null, false, null, request, result).metrics
        assertTrue(simple.isNotEmpty() && simple.all { it.primary })
        assertFalse(Metrics.PARTICLES_GT03 in simple)
        assertTrue(Metrics.PM10 in simple)
        val detailed = fileChartState(null, true, null, request, result).metrics
        assertEquals(Metrics.all, detailed)
    }
}

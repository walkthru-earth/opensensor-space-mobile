@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosDailyDerivedStoreTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/derived-test-" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() { NSFileManager.defaultManager.removeItemAtPath(root, null) }

    private val archive = object : ArchiveStore {
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = true
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet"))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(station: String?): List<ArchiveKey> = emptyList()
    }

    @Test
    fun persistedDaysSurviveRestartAndChangedAnchorsRemoveObsoletePartitions() = runTest {
        val (source, anchor) = dailyFixture()
        val record = source.record
        val now = anchor.utcNs / 1_000_000_000L + 200_000L
        val paths = IosDailyDerivedStore(root).rebuild(record.station, listOf(record), archive, mapOf(anchor.boot to anchor), now)
        assertEquals(2, paths.size)
        assertEquals(64L, paths.sumOf { ParquetFile.open(LocalFileReader(it)).use { f -> f.numRows } })
        val manifest = paths.first().removeSuffix(".parquet") + ".manifest.json"
        fun manifestText(): String = LocalFileReader(manifest).use { reader -> reader.read(0, reader.size.toInt()).decodeToString() }
        val firstManifest = manifestText()
        assertTrue(firstManifest.contains("time_anchor"))
        assertEquals(paths, IosDailyDerivedStore(root).rebuild(record.station, listOf(record), archive, mapOf(anchor.boot to anchor), now + 1))
        assertEquals(firstManifest, manifestText(), "restart preserves unchanged cache files")
        val shifted = anchor.copy(utcNs = anchor.utcNs + 2 * StationDays.DAY_NS)
        val changed = record.copy(estFirstUtcNs = record.estFirstUtcNs!! + 2 * StationDays.DAY_NS, estLastUtcNs = shifted.utcNs)
        val corrected = IosDailyDerivedStore(root).rebuild(record.station, listOf(changed), archive, mapOf(shifted.boot to shifted), now + 200_000L)
        assertEquals(2, corrected.size)
        assertEquals(64L, corrected.sumOf { ParquetFile.open(LocalFileReader(it)).use { f -> f.numRows } })
        assertTrue(paths.none { it in corrected })
        paths.forEach { assertFalse(NSFileManager.defaultManager.fileExistsAtPath(it)) }
    }
}

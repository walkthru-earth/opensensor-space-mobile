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
import earth.walkthru.aqsync.data.protocol.sha256Hex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDailyDerivedStoreTest {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/derived-test-" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() { NSFileManager.defaultManager.removeItemAtPath(root, null) }

    private var reads = 0
    private val archive = object : ArchiveStore {
        override suspend fun partialLengths(station: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = true
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader {
            reads++
            return ByteArrayReader(fixtureBytes("parquet/device/v1-none-64rows-unsynced.parquet"))
        }
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

    @Test
    fun aDayAWaitingRecordHoldsKeepsItsFilesUntilTheRecordIsGone() = runTest {
        val (source, anchor) = dailyFixture()
        val record = source.record
        val anchors = mapOf(anchor.boot to anchor)
        val now = anchor.utcNs / 1_000_000_000L + 200_000L
        val store = IosDailyDerivedStore(root)
        val paths = store.rebuild(record.station, listOf(record), archive, anchors, now)
        assertEquals(2, paths.size)
        fun allExist() = paths.all { NSFileManager.defaultManager.fileExistsAtPath(it) && NSFileManager.defaultManager.fileExistsAtPath(it.removeSuffix(".parquet") + ".manifest.json") }

        // Reopened by an index migration, the record keeps its span and holds both days (#87).
        assertEquals(emptyList(), store.rebuild(record.station, listOf(record.copy(indexedAtEpochMs = null)), archive, anchors, now + 1))
        assertTrue(allExist())
        // Without a span it could land on any day, so every day stays.
        val spanless = record.copy(indexedAtEpochMs = null, estFirstUtcNs = null, estLastUtcNs = null)
        store.rebuild(record.station, listOf(spanless), archive, anchors, now + 2)
        assertTrue(allExist())
        // A failed index holds nothing, so its days go.
        store.rebuild(record.station, listOf(record.copy(indexError = "bad")), archive, anchors, now + 3)
        paths.forEach { assertFalse(NSFileManager.defaultManager.fileExistsAtPath(it)) }
    }

    @Test
    fun dailyPassPersistsTiersWithoutASecondSourceDecode() = runTest {
        val (source, anchor) = dailyFixture()
        val record = source.record
        val atomic = earth.walkthru.aqsync.storage.IosAtomicFiles("$root/temps")
        val disk = IosFileTierStore(root, atomic)
        val now = anchor.utcNs / 1_000_000_000L + 200_000L
        val anchors = mapOf(anchor.boot to anchor)
        val index = object : earth.walkthru.aqsync.archive.ArchiveIndex {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(listOf(record))
            override fun observeStation(station: String) = observeAll()
            override suspend fun get(key: ArchiveKey) = record.takeIf { it.key == key }
            override suspend fun put(record: earth.walkthru.aqsync.archive.ArchiveRecord) = error("read only")
            override suspend fun remove(key: ArchiveKey) = error("read only")
            override suspend fun unindexed(limit: Int) = emptyList<earth.walkthru.aqsync.archive.ArchiveRecord>()
            override suspend fun timeline(station: String, fromNs: Long, toNs: Long) = listOf(record).filter { it.onTimeline(fromNs, toNs) }
            override suspend fun indexedForStation(station: String) = listOf(record)
            override suspend fun stations() = listOf(record.station)
        }
        val derived = IosDailyDerivedStore(root, atomic, disk)
        var prepares = 0
        val tiers = ArchiveHistoryTiers(archive, index, io = StandardTestDispatcher(testScheduler), tierStore = disk, shareScope = backgroundScope, beforeBuild = { station, files, _ ->
            prepares++
            derived.rebuild(station, files, archive, anchors, now)
        })
        val spans = List(3) { async { tiers.observeDays(record.station).first() } }.awaitAll()
        val daySpan = spans.first()
        assertTrue(spans.all { it == daySpan })
        assertEquals(1, prepares, "concurrent observers prepare the daily pass once")
        assertEquals(2, daySpan.count)
        assertEquals(2, reads, "one source open for each UTC day builds both daily files and tiers")
        assertEquals(0, tiers.decodedDays)
        val restarted = ArchiveHistoryTiers(archive, index, tierStore = IosFileTierStore(root, atomic))
        assertEquals(daySpan, restarted.observeDays(record.station).first())
        assertEquals(2, reads)
        assertEquals(0, restarted.decodedDays)
    }

    @Test
    fun openRejectsStaleMissingAndOlderCacheSignatures() = runTest {
        val (source, anchor) = dailyFixture()
        val record = source.record
        val anchors = mapOf(anchor.boot to anchor)
        val now = anchor.utcNs / 1_000_000_000L + 200_000L
        val atomic = earth.walkthru.aqsync.storage.IosAtomicFiles("$root/temps")
        val derived = IosDailyDerivedStore(root, atomic)
        val path = derived.rebuild(record.station, listOf(record), archive, anchors, now).first()
        val day = StationDays.dayOf(record.estFirstUtcNs!!)
        assertNotNull(derived.open(record.station, day, listOf(record), anchors)).close()
        assertNull(derived.open(record.station, day, listOf(record.copy(crc32 = record.crc32 + 1)), anchors))
        val manifest = path.removeSuffix(".parquet") + ".manifest.json"
        val text = LocalFileReader(manifest).use { reader -> reader.read(0, reader.size.toInt()).decodeToString() }
        val oldSignatureText = "${DailyParquetBuilder.BUILDER}:true\n${record.name}:${record.bytes}:${record.crc32}:${record.sha256}:est${anchor.boot}:${anchor.utcNs}:${anchor.monoUs}\n"
        val oldSignature = earth.walkthru.aqsync.data.protocol.ParquetVerify.sha256Hex(oldSignatureText.encodeToByteArray())
        val legacy = kotlinx.serialization.json.Json.parseToJsonElement(text).let { parsed ->
            kotlinx.serialization.json.JsonObject((parsed as kotlinx.serialization.json.JsonObject) + ("source_signature" to kotlinx.serialization.json.JsonPrimitive(oldSignature)))
        }
        atomic.writeAtomically(manifest, legacy.toString().encodeToByteArray(), earth.walkthru.aqsync.storage.StorageClass.Rebuildable)
        assertNull(derived.open(record.station, day, listOf(record), anchors))
        NSFileManager.defaultManager.removeItemAtPath(manifest, null)
        assertNull(derived.open(record.station, day, listOf(record), anchors))
        derived.rebuild(record.station, listOf(record), archive, anchors, now)
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        assertNull(derived.open(record.station, day, listOf(record), anchors))
    }

    @Test
    fun anOpenedDescriptorRetainsItsSnapshotAcrossReplacement() = runTest {
        val (source, anchor) = dailyFixture()
        val record = source.record
        val anchors = mapOf(anchor.boot to anchor)
        val now = anchor.utcNs / 1_000_000_000L + 200_000L
        val derived = IosDailyDerivedStore(root)
        derived.rebuild(record.station, listOf(record), archive, anchors, now)
        val day = StationDays.dayOf(record.estFirstUtcNs!!)
        val opened = assertNotNull(derived.open(record.station, day, listOf(record), anchors))
        try {
            val snapshot = opened.readRanges(listOf(0L until opened.id().size)).single().toByteArray()
            val shifted = anchor.copy(utcNs = anchor.utcNs + 1_000_000_000L)
            val changed = record.copy(estFirstUtcNs = record.estFirstUtcNs!! + 1_000_000_000L, estLastUtcNs = record.estLastUtcNs!! + 1_000_000_000L)
            val changedAnchors = mapOf(shifted.boot to shifted)
            derived.rebuild(record.station, listOf(changed), archive, changedAnchors, now)
            assertContentEquals(snapshot, opened.readRanges(listOf(0L until opened.id().size)).single().toByteArray())
            val current = assertNotNull(derived.open(record.station, day, listOf(changed), changedAnchors))
            try {
                assertFalse(snapshot.contentEquals(current.readRanges(listOf(0L until current.id().size)).single().toByteArray()), "the replacement contains the shifted timeline")
            } finally {
                current.close()
            }
        } finally {
            opened.close()
        }
    }
}

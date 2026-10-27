package space.opensensor.data.history

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.toRecord
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.local.database.AppDatabase
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.local.database.DerivedArchivedFileQueries
import space.opensensor.data.local.database.DerivedDay
import space.opensensor.data.local.database.DerivedDayDao
import space.opensensor.data.local.database.MetricBucket
import space.opensensor.data.local.database.MetricBucketDao
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileArchive
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.DerivedQuota
import space.opensensor.storage.JvmAtomicFiles
import space.opensensor.storage.JvmStorageTree
import space.opensensor.storage.QuotaEnforcer
import space.opensensor.storage.StorageClass
import space.opensensor.storage.StorageRole
import space.opensensor.storage.StorageRoots
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The deriver over in-memory DAOs and a temp archive: staleness by source signature, tiers and daily files together. */
class HistoryDeriverTest {
    init {
        AirFamily.install()
    }

    private fun bytes(path: String) = migrateLegacyFooter(checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/$path")).readBytes())
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"

    @Test
    fun `tiers and daily cache of the previous derive version rebuild without a source change`() = runBlocking {
        // Version 9 writes the app_day entry in the daily file. Installed days must rebuild.
        assertEquals(9, HistoryDeriver.DERIVE_VERSION)
        val tmp = java.nio.file.Files.createTempDirectory("aq-derive-upgrade").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val buckets = InMemoryBuckets()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val store = DerivedStore(tmp)
            val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, derivedQuota(tmp), indexer.passes(files))
            val name = "thing=$thing/day=2026-09-17/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v3-board-2rg-105rows.parquet"))
            }
            val originalBytes = raw.readBytes()
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            assertEquals(1, deriver.deriveStale().daysRebuilt)
            val expectedTiers = buckets.rows.toList()
            val originalSources = files.rows.toList()
            val current = days.rows.single()
            val oldDigest = MessageDigest.getInstance("SHA-256").apply {
                update("derive-v${HistoryDeriver.DERIVE_VERSION - 1}\n".toByteArray())
                update("$name:1\n".toByteArray())
            }
            val oldSignature = HistoryDeriver.hex(oldDigest.digest())
            assertFalse(oldSignature == current.sourceSignature)
            // Model an installed cache of the previous version. Its pointer and unchanged source set are
            // present, but its tier values and daily payload predate this version.
            days.upsert(current.copy(sourceSignature = oldSignature))
            buckets.rows.clear()
            buckets.rows += expectedTiers.map { it.copy(rows = 777, validRows = 777, mean = 999f, min = 999f, max = 999f) }
            val daily = store.dayFile(thing, current.epochDay)
            assertTrue(daily.isFile)
            assertTrue(days.rows.single().dailyFile != null)
            daily.writeText("obsolete daily bytes")
            val manifest = store.manifestFile(thing, current.epochDay)
            manifest.writeText(Json.encodeToString(Json.decodeFromString<DailyManifest>(manifest.readText()).copy(sourceSignature = oldSignature)))

            val upgraded = deriver.deriveStale()
            assertEquals(1, upgraded.daysChecked)
            assertEquals(1, upgraded.daysRebuilt)
            assertEquals(expectedTiers, buckets.rows)
            assertEquals(HistoryDeriver.signatureOf(originalSources.map { it.toRecord() }), days.rows.single().sourceSignature)
            assertEquals(days.rows.single().sourceSignature, Json.decodeFromString<DailyManifest>(manifest.readText()).sourceSignature)
            space.opensensor.data.history.parquet.ParquetFile.open(daily).use { assertEquals(105L, it.numRows) }
            assertEquals(originalSources, files.rows)
            assertTrue(originalBytes.contentEquals(raw.readBytes()))
            assertEquals(0, deriver.deriveStale().daysRebuilt)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `every derive entry point waits for a History pass in flight`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-derive-pass").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val passes = indexer.passes(files)
            val deriver = HistoryDeriver(archive, files, InMemoryBuckets(), days, DerivedStore(tmp), indexer, derivedQuota(tmp), passes)
            val name = "thing=$thing/day=2026-09-17/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v3-board-2rg-105rows.parquet"))
            }
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            val entries = listOf<Pair<String, suspend () -> Unit>>(
                "deriveStale" to { deriver.deriveStale() },
                "deriveAfterSync" to { deriver.deriveAfterSync(0) },
                "ensureDailyFiles" to { deriver.ensureDailyFiles() },
            )
            for ((entry, derive) in entries) {
                val inPass = CompletableDeferred<Unit>()
                val endPass = CompletableDeferred<Unit>()
                // A backfill or another derive holds the pass.
                val other = launch(Dispatchers.Default) { passes.derivePass { inPass.complete(Unit); endPass.await() } }
                inPass.await()
                val waiting = launch(Dispatchers.Default) { derive() }
                assertNull("$entry waits for the pass in flight", withTimeoutOrNull(2_000) { waiting.join() })
                endPass.complete(Unit)
                other.join()
                waiting.join()
            }
            assertEquals(1, days.rows.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `derived reads require a matching receipt and keep opened bytes across replacement`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-derived-read").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val store = DerivedStore(tmp)
            val deriver = HistoryDeriver(archive, files, InMemoryBuckets(), days, store, indexer, derivedQuota(tmp), indexer.passes(files))
            val name = "thing=$thing/day=2026-09-17/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v3-board-2rg-105rows.parquet"))
            }
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            deriver.deriveStale()
            val reader = RoomDerivedDayFiles(store, days)
            val sources = files.rows.map { it.toRecord() }
            val manifest = store.manifestFile(thing, 20713)
            val validReceipt = manifest.readText()
            reader.open(thing, 20713, sources, emptyMap())!!.close()

            // Room still carries the old signature through file replacement. The manifest is the
            // receipt for the bytes at the path, so a missing or changed receipt forces raw reads.
            assertTrue(manifest.delete())
            assertEquals(null, reader.open(thing, 20713, sources, emptyMap()))
            manifest.writeText(Json.encodeToString(Json.decodeFromString<DailyManifest>(validReceipt).copy(sourceSignature = "another-build")))
            assertEquals(null, reader.open(thing, 20713, sources, emptyMap()))
            manifest.writeText("broken receipt")
            assertEquals(null, reader.open(thing, 20713, sources, emptyMap()))
            manifest.writeText(validReceipt)
            val opened = reader.open(thing, 20713, sources, emptyMap())!!
            opened.use {
                val tail = it.readTail(4).toByteArray()
                store.atomic.writeAtomically(store.dayFile(thing, 20713).path, ByteArray(32), StorageClass.Rebuildable)
                assertTrue(tail.contentEquals(it.readTail(4).toByteArray()))
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `cache setup and commit failures keep tiers and retry the daily file`() = runBlocking {
        for (failureStage in listOf("temp", "commit", "manifest")) {
            val tmp = java.nio.file.Files.createTempDirectory("aq-cache-failure").toFile()
            try {
                var failing = true
                val delegate = JvmAtomicFiles(File(tmp, AtomicFiles.TEMP_DIR))
                val atomic = object : AtomicFiles by delegate {
                    override fun createTemp(suffix: String): String {
                        if (failing && failureStage == "temp") throw IOException("temp unavailable")
                        return delegate.createTemp(suffix)
                    }
                    override fun commitReplace(from: String, to: String, cls: StorageClass) {
                        if (failing && failureStage == "commit") throw IOException("commit unavailable")
                        delegate.commitReplace(from, to, cls)
                    }
                    override fun writeAtomically(target: String, bytes: ByteArray, cls: StorageClass) {
                        if (failing && failureStage == "manifest") throw IOException("manifest unavailable")
                        delegate.writeAtomically(target, bytes, cls)
                    }
                }
                val archive = FileArchive(tmp)
                val files = InMemoryFiles()
                val buckets = InMemoryBuckets()
                val days = InMemoryDays()
                val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
                val store = DerivedStore(tmp, atomic)
                val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, derivedQuota(tmp), indexer.passes(files))
                val name = "thing=$thing/day=2026-09-17/raw.parquet"
                val raw = archive.fileFor(thing, name).apply {
                    parentFile!!.mkdirs()
                    writeBytes(bytes("device/v3-board-2rg-105rows.parquet"))
                }
                files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)

                assertEquals(1, deriver.deriveStale().daysRebuilt)
                assertEquals(105, buckets.rows.first { it.metricId == AirMetrics.PM25.id && it.resolution == 86_400L }.rows)
                assertEquals(null, days.rows.single().dailyFile)
                assertTrue(days.rows.single().sourceSignature.endsWith(":no-daily-file"))
                failing = false
                assertEquals(1, deriver.deriveStale().daysRebuilt)
                assertTrue(store.dayFile(thing, 20713).isFile)
                assertTrue(days.rows.single().dailyFile != null)
                assertEquals(0, deriver.deriveStale().daysRebuilt)
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    @Test
    fun `deriver rebuilds a day only when its source set changes`() = runBlocking {
        val tmp = File.createTempFile("aq-derive", "").apply { delete(); mkdirs(); deleteOnExit() }
        val archive = FileArchive(tmp)
        val files = InMemoryFiles()
        val buckets = InMemoryBuckets()
        val days = InMemoryDays()
        val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
        val store = DerivedStore(tmp)
        val quota = derivedQuota(tmp)
        val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, quota, indexer.passes(files))

        suspend fun put(name: String, resource: String, crc: Long) {
            archive.fileFor(thing, name).apply { parentFile!!.mkdirs(); writeBytes(bytes(resource)) }
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = 1, crc32 = crc, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), archive.fileFor(thing, name))
        }
        put("thing=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet", "device/v3-board-2rg-105rows.parquet", 1)
        put("thing=$thing/unsynced/boot=x/data_unsynced_x_0-89-0.parquet", "device/v2-none-90rows-unsynced.parquet", 2)

        val first = deriver.deriveStale()
        assertEquals(1, first.daysChecked)
        assertEquals(1, first.daysRebuilt)
        assertEquals(105L, first.rowsAggregated)
        assertEquals(4, buckets.rows.map { it.resolution to it.startEpochS }.distinct().size) // 2 × 15 min + 1 h + 1 day, one row per metric each
        assertTrue(buckets.rows.any { it.metricId == AirMetrics.PM25.id } && buckets.rows.any { it.metricId == AirMetrics.BOARD_TEMP.id } && buckets.rows.none { it.metricId == AirMetrics.HUMIDITY.id })
        assertEquals(1, days.rows.size)
        // The derived daily file exists, is read back by our own reader, and carries the manifest index column.
        val dayFile = store.dayFile(thing, 20713)
        assertTrue(dayFile.isFile)
        assertEquals("thing/day=2026-09-17.parquet".substringAfter('/'), dayFile.name)
        val daily = space.opensensor.data.history.parquet.ParquetFile.open(dayFile)
        assertEquals(105L, daily.numRows)
        assertEquals(1, daily.rowGroupCount) // 09:42–09:59Z is one UTC hour
        assertEquals(78, daily.leaves.size) // 77 + source_file_index
        assertEquals(ParquetLogicalType.TimeUnit.MICROS, daily.leaf("event_time_utc_us").timestampUtcUnit)
        // A day file never carries the contract key an indexer reads.
        assertTrue(OpenSensorFooter.KEY !in daily.metadata.keyValueMetadata)
        assertTrue(daily.metadata.keyValueMetadata.keys.none(LegacyFooterKeys::isLegacy))
        assertTrue(daily.metadata.keyValueMetadata["complete"] in setOf("true", "false"))
        assertTrue(store.manifestFile(thing, 20713).readText().contains("\"rows_used\":105"))
        assertEquals("day=2026-09-17.parquet", days.rows.single().dailyFile?.substringAfter('/'))
        // For tools/validate_parquet_samples.py (PyArrow + DuckDB): a real derived day built from the board's file.
        File("build/parquet-samples").apply { mkdirs() }.let { dir ->
            dayFile.copyTo(File(dir, "derived-day.parquet"), overwrite = true)
            store.manifestFile(thing, 20713).copyTo(File(dir, "derived-day.manifest.json"), overwrite = true)
        }

        val second = deriver.deriveStale()
        assertEquals(0, second.daysRebuilt)

        // An evicted day file is a cache miss (#61). A derive pass does not rebuild it, so the quota
        // never makes passes churn, and the export rebuilds it when it reads the tree.
        assertTrue(dayFile.delete())
        assertEquals(0, deriver.deriveStale().daysRebuilt)
        assertFalse(dayFile.isFile)
        assertEquals(1, deriver.ensureDailyFiles())
        assertTrue(dayFile.isFile)
        assertEquals(105L, space.opensensor.data.history.parquet.ParquetFile.open(dayFile).use { it.numRows })
        assertEquals(0, deriver.ensureDailyFiles())
        assertEquals(0, deriver.deriveStale().daysRebuilt)

        // A second file for the same day (here: the same bytes under another name and CRC) changes the signature.
        put("thing=$thing/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet", "device/v3-board-2rg-105rows.parquet", 3)
        val third = deriver.deriveStale()
        assertEquals(1, third.daysRebuilt)
        assertEquals(210L, third.rowsAggregated) // rows read
        assertEquals(105, buckets.rows.first { it.resolution == 86_400L && it.metricId == AirMetrics.PM25.id }.rows) // rows counted: duplicates (same boot + sequence) skipped, like the daily file
        assertEquals(2, days.rows.single().sourceFiles)
        // Same rows under two names: the daily file de-duplicates on (boot, sequence) — 105 rows, 2 sources in the manifest.
        assertEquals(105L, space.opensensor.data.history.parquet.ParquetFile.open(store.dayFile(thing, 20713)).numRows)
        assertTrue(store.manifestFile(thing, 20713).readText().contains("\"rows_used\":0"))

        // A second real v6 file (the full 10:00–10:29Z window, 180 rows) joins the day: 285 rows, two UTC hours → two row groups.
        put("thing=$thing/year=2026/month=09/day=17/data_1000_495ccef9f3eb8c5917070c921c9baec8_107-286-3.parquet", "device/v3-board-2rg-180rows.parquet", 4)
        val fifth = deriver.deriveStale()
        assertEquals(1, fifth.daysRebuilt)
        val twoWindows = space.opensensor.data.history.parquet.ParquetFile.open(store.dayFile(thing, 20713))
        assertEquals(285L, twoWindows.numRows)
        assertEquals(2, twoWindows.rowGroupCount)
        assertEquals(listOf(105L, 180L), twoWindows.metadata.rowGroups.map { it.numRows })
        val seq = twoWindows.readColumn("sequence") as space.opensensor.data.history.parquet.ColumnValues.Int64
        for (i in 1 until seq.size) assertEquals("contiguous across the two device files", seq.values[i - 1] + 1, seq.values[i])
        assertEquals(285, buckets.rows.first { it.resolution == 86_400L && it.metricId == AirMetrics.PM25.id }.rows)
        File("build/parquet-samples").apply { mkdirs() }.let { dir ->
            store.dayFile(thing, 20713).copyTo(File(dir, "derived-day-two-windows.parquet"), overwrite = true)
            store.manifestFile(thing, 20713).copyTo(File(dir, "derived-day-two-windows.manifest.json"), overwrite = true)
        }

        // Removing every dated file removes the day's tiers.
        files.rows.removeAll { it.name.contains("day=17") }
        val fourth = deriver.deriveStale()
        assertEquals(1, fourth.daysRebuilt)
        assertTrue(buckets.rows.isEmpty())
        assertTrue(days.rows.isEmpty())
        assertFalse(store.dayFile(thing, 20713).exists())
    }

    @Test
    fun `a post-sync pass rebuilds only touched days and hands older stale days to the backfill`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-derive-touched").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val buckets = InMemoryBuckets()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val store = DerivedStore(tmp)
            var backfills = 0
            val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, derivedQuota(tmp), indexer.passes(files)) { backfills++ }

            suspend fun put(name: String, resource: String, crc: Long, syncedAt: Long) {
                archive.fileFor(thing, name).apply { parentFile!!.mkdirs(); writeBytes(bytes(resource)) }
                files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = 1, crc32 = crc, sha256 = "s", syncedAtEpochMs = syncedAt, sourceDevice = "fake"), archive.fileFor(thing, name))
            }
            put("thing=$thing/year=2026/month=09/day=08/data_2015_763038f2396a5d68927ac324c4a744a2_3-6-1.parquet", "device/v2-lz4-4rows-dated.parquet", 1, syncedAt = 1)
            put("thing=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet", "device/v3-board-2rg-105rows.parquet", 2, syncedAt = 1)
            assertEquals(2, deriver.deriveStale().daysRebuilt)

            // The 09-08 tiers go stale without a sync (an older derive version), then a sync adds a 09-17 file.
            days.upsert(days.rows.single { it.epochDay == 20704L }.copy(sourceSignature = "old"))
            put("thing=$thing/year=2026/month=09/day=17/data_1000_a_107-211-3.parquet", "device/v3-board-2rg-105rows.parquet", 3, syncedAt = 100)

            val afterSync = deriver.deriveAfterSync(sinceEpochMs = 100)
            assertEquals(1, afterSync.daysRebuilt)
            assertEquals(1, afterSync.daysDeferred)
            assertEquals(1, backfills)
            assertEquals(2, days.rows.single { it.epochDay == 20713L }.sourceFiles)
            assertEquals("old", days.rows.single { it.epochDay == 20704L }.sourceSignature)

            // Nothing arrived since: no work and no backfill request.
            val idle = deriver.deriveAfterSync(sinceEpochMs = 200)
            assertEquals(0, idle.daysChecked)
            assertEquals(1, backfills)

            // The backfill pass picks up the deferred day.
            val backfill = deriver.deriveStale()
            assertEquals(1, backfill.daysRebuilt)
            assertEquals(0, backfill.daysDeferred)
            assertTrue(days.rows.single { it.epochDay == 20704L }.sourceSignature != "old")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a clock receipt re-dates files only inside a History pass`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-clock-pass").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val passes = indexer.passes(files)
            val deriver = HistoryDeriver(archive, files, InMemoryBuckets(), InMemoryDays(), DerivedStore(tmp), indexer, derivedQuota(tmp), passes)
            val name = "thing=$thing/unsynced/boot=e452563e96c72ca057a48924550779b6/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v1-none-64rows-unsynced.parquet"))
            }
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            val before = files.rows.single()
            val utcNs = 1_788_912_000_123_456_000L
            val inPass = CompletableDeferred<Unit>()
            val endPass = CompletableDeferred<Unit>()
            // A derive in flight reads the estimate columns for its source sets.
            val other = launch(Dispatchers.Default) { passes.derivePass { inPass.complete(Unit); endPass.await() } }
            inPass.await()
            deriver.persistClockAnchor(thing, before.bootId!!, utcNs, before.lastMonoUs!!)
            val receipt = launch(Dispatchers.Default) { deriver.deriveThing(thing) }
            assertNull("the re-dating waits for the pass in flight", withTimeoutOrNull(2_000) { receipt.join() })
            assertNull("no estimate is written beside the derive", files.rows.single().estLastUtcNs)
            endPass.complete(Unit)
            other.join()
            receipt.join()
            assertEquals(utcNs, files.rows.single().estLastUtcNs)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `clock receipt partitions unknown UTC across midnight and survives a new indexer`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-clock-derive").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val buckets = InMemoryBuckets()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val store = DerivedStore(tmp)
            val quota = derivedQuota(tmp)
            val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, quota, indexer.passes(files))
            val name = "thing=$thing/unsynced/boot=e452563e96c72ca057a48924550779b6/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v1-none-64rows-unsynced.parquet"))
            }
            val original = raw.readBytes()
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            val before = files.rows.single()
            assertEquals(0, deriver.deriveStale().daysChecked)
            val utcNs = 1_788_912_000_123_456_000L // midnight + submillisecond fraction
            deriver.persistClockAnchor(thing, before.bootId!!, utcNs, before.lastMonoUs!!)
            deriver.deriveThing(thing)
            assertEquals(2, days.rows.size)
            assertEquals(64L, days.rows.sumOf { it.rows.toLong() })
            assertTrue(raw.readBytes().contentEquals(original))
            assertEquals(0L, files.rows.single().utcRows)
            assertEquals(utcNs, files.rows.single().estLastUtcNs)
            assertEquals(0, deriver.deriveStale().daysRebuilt)
            val restarted = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            assertEquals(0, restarted.estimateTimes())
            assertEquals(utcNs, files.rows.single().estLastUtcNs)
            for (day in days.rows) {
                File("build/parquet-samples").apply { mkdirs() }.let { outputs ->
                    store.dayFile(thing, day.epochDay).copyTo(File(outputs, "derived-clock-day-${day.epochDay}.parquet"), overwrite = true)
                    store.manifestFile(thing, day.epochDay).copyTo(File(outputs, "derived-clock-day-${day.epochDay}.manifest.json"), overwrite = true)
                }
                val parquet = space.opensensor.data.history.parquet.ParquetFile.open(store.dayFile(thing, day.epochDay))
                val actual = parquet.readColumn("event_time_utc_us")
                val estimated = parquet.readColumn("event_time_utc_estimated_us")
                for (row in 0 until actual.size) {
                    assertTrue(actual.isNull(row))
                    assertEquals(day.epochDay, ThingDays.dayOf(estimated.asLong(row)!! * 1_000))
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `the signature hex is the lower-case two digits per byte it always was`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }, HistoryDeriver.hex(bytes))
        assertEquals("", HistoryDeriver.hex(ByteArray(0)))
    }

    @Test
    fun `collection clock receipt persists without reconciling or deriving history`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("aq-clock-persist").toFile()
        try {
            val archive = FileArchive(tmp)
            val files = InMemoryFiles()
            val buckets = InMemoryBuckets()
            val days = InMemoryDays()
            val indexer = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val store = DerivedStore(tmp)
            val deriver = HistoryDeriver(archive, files, buckets, days, store, indexer, derivedQuota(tmp), indexer.passes(files))
            val name = "thing=$thing/unsynced/boot=00000000000000000000000000000000/raw.parquet"
            val raw = archive.fileFor(thing, name).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes("device/v1-none-64rows-unsynced.parquet"))
            }
            files.rows += indexer.indexed(ArchivedFile(thing = thing, name = name, bytes = raw.length(), crc32 = 1, sha256 = "s", syncedAtEpochMs = 1, sourceDevice = "fake"), raw)
            val before = files.rows.single()
            val utcNs = 1_788_912_000_123_456_000L
            deriver.persistClockAnchor(thing, before.bootId!!, utcNs, before.lastMonoUs!!)

            // Restart reads the exact durable acknowledgement, while the older archive row and
            // derived caches stay untouched until a cancellable pass explicitly reconciles them.
            val restarted = HistoryIndexer(archive, files, DeviceCoordinator(), CoreS3Files)
            val anchor = restarted.clockAnchors.anchors(thing).single()
            assertEquals(before.bootId, anchor.boot)
            assertEquals(utcNs, anchor.utcNs)
            assertEquals(before.lastMonoUs, anchor.monoUs)
            assertEquals(before, files.rows.single())
            assertTrue(days.rows.isEmpty())
            assertTrue(buckets.rows.isEmpty())
            assertEquals(1, restarted.estimateTimes())
            assertEquals(utcNs, files.rows.single().estLastUtcNs)
            assertTrue(days.rows.isEmpty())
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** The derived quota over [tmp]'s derived root, with the default budget. */
    private fun derivedQuota(tmp: File) = DerivedQuota(
        QuotaEnforcer(StorageRoots(mapOf(StorageRole.Derived to File(tmp, "derived/v1").absolutePath)), JvmStorageTree()),
    )

    private class InMemoryFiles : DerivedArchivedFileQueries {
        val rows = mutableListOf<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows.toList())
        override fun observeForThing(thing: String): Flow<List<ArchivedFile>> = flowOf(rows)
        override suspend fun forThing(thing: String) = rows.filter { it.thing == thing }
        override suspend fun get(thing: String, name: String) = rows.firstOrNull { it.thing == thing && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun upsert(file: ArchivedFile) {
            rows.removeAll { it.thing == file.thing && it.name == file.name }
            rows += file
        }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long {
            if (rows.any { it.thing == file.thing && it.name == file.name }) return -1L
            rows += file
            return rows.size.toLong()
        }
        override suspend fun delete(thing: String, name: String) {
            rows.removeAll { it.thing == thing && it.name == name }
        }
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(0)

        // The deriver lists a thing once and splits it by day (#63), a time query per day is a regression.
        override suspend fun withUtcBetween(thing: String, fromNs: Long, toNs: Long): List<ArchivedFile> = error("the deriver must not query per day")
        override suspend fun indexedForThing(thing: String) = rows.filter { it.thing == thing && it.isIndexed }
        override suspend fun things() = rows.map { it.thing }.distinct()
    }

    private class InMemoryBuckets : MetricBucketDao {
        val rows = mutableListOf<MetricBucket>()
        override fun observe(thing: String, metricId: String, resolution: Long, fromS: Long, toS: Long): Flow<List<MetricBucket>> = flowOf(rows)
        override fun observeFirstDay(thing: String): Flow<Long?> = flowOf(null)
        override fun observeLastDay(thing: String): Flow<Long?> = flowOf(null)
        override fun observeDayCount(thing: String): Flow<Int> = flowOf(0)
        override fun observeAllDays(metricId: String): Flow<List<MetricBucket>> = flowOf(rows.filter { it.resolution == 86_400L && it.metricId == metricId })
        override suspend fun deleteBetween(thing: String, fromS: Long, toS: Long) {
            rows.removeAll { it.thing == thing && it.startEpochS >= fromS && it.startEpochS < toS }
        }
        override suspend fun insertAll(rows: List<MetricBucket>) {
            this.rows += rows
        }
    }

    private class InMemoryDays : DerivedDayDao {
        val rows = mutableListOf<DerivedDay>()
        override suspend fun forThing(thing: String) = rows.filter { it.thing == thing }
        override suspend fun get(thing: String, epochDay: Long) = rows.firstOrNull { it.thing == thing && it.epochDay == epochDay }
        override suspend fun upsert(day: DerivedDay) {
            rows.removeAll { it.thing == day.thing && it.epochDay == day.epochDay }
            rows += day
        }
        override suspend fun delete(thing: String, epochDay: Long) {
            rows.removeAll { it.thing == thing && it.epochDay == epochDay }
        }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override suspend fun things() = rows.map { it.thing }.distinct()
    }

    /** The shared pass order over this test's index, as the app wires it. */
    private fun HistoryIndexer.passes(files: ArchivedFileDao) = HistoryOrchestrator(repair, RoomArchiveIndex(files))
}

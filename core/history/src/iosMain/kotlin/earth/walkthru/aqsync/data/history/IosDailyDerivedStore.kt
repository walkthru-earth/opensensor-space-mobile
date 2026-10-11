@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.LocalFile
import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import earth.walkthru.aqsync.data.history.parquet.LocalRangeSource
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.sha256Hex
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import platform.posix.EINVAL
import platform.posix.ENOTSUP
import platform.posix.F_FULLFSYNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open
import platform.posix.write
import kotlin.time.Instant

/**
 * Atomic on-disk daily Parquet cache for iOS. [root] is `Library/Caches` (#61), which iOS may purge
 * and never backs up; the shared layout puts files in `derived/v1/<station>/day=YYYY-MM-DD.parquet`.
 * Rebuild runs after index repair on startup, after downloads and after durable clock receipts
 * arrive. It needs no network. Temps commit with [AtomicFiles.commitReplace] as Rebuildable data
 * (#60). Pass the app's [AtomicFiles], whose temp directory is on the same volume.
 *
 * A day whose manifest still carries the current signature but whose Parquet is gone was evicted by
 * the quota or purged by iOS. It is a cache miss, skipped here and kept, so eviction never makes
 * rebuild passes churn.
 *
 * It is also the Day view's [DerivedDayFiles] (#63): a day file is read only while its manifest
 * carries the signature of exactly the sources the view would read.
 */
class IosDailyDerivedStore(
    root: String,
    private val atomic: AtomicFiles = IosAtomicFiles("${root.trimEnd('/')}/${AtomicFiles.TEMP_DIR}"),
    private val tierStore: TierStore = TierStore.NONE,
) : DerivedDayFiles {
    private val root = root.trimEnd('/')
    private val mutex = Mutex()
    private val fm get() = NSFileManager.defaultManager
    private val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    /** Rebuild changed UTC days, remove obsolete partitions, and return the daily paths present now. */
    suspend fun rebuild(
        station: String,
        files: List<ArchiveRecord>,
        archive: ArchiveStore,
        anchors: Map<String, TimeEstimate.Anchor>,
        nowEpochS: Long,
    ): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = checked("$root/${ArchiveLayout.derivedStationDir(station)}")
            check(fm.createDirectoryAtPath(directory, true, protection, null)) { "cannot create derived directory" }
            val records = files.filter { it.station == station && it.indexed && it.isTelemetry }
            // One pass over the station's records for every day, not a filter per day (#63).
            val byDay = StationDays.sourcesByDay(records) { it }
            val paths = mutableListOf<String>()
            // Day files this pass keeps, built or evicted. Everything else in the directory is stale.
            val kept = HashSet<String>()
            for (day in byDay.keys.sorted()) {
                val sources = byDay.getValue(day)
                val complete = nowEpochS >= (day + 1) * 86_400 + DailyParquetBuilder.COMPLETE_GRACE_S
                val signature = signature(sources, anchors, complete)
                val target = checked("$root/${ArchiveLayout.derivedDayPath(station, day)}")
                val manifest = checked("$root/${ArchiveLayout.derivedManifestPath(station, day)}")
                if (cachedSignature(manifest) == signature) {
                    if (isFile(target)) paths += target
                    kept += target.substringAfterLast('/')
                    continue
                }
                val temporary = atomic.createTemp(".parquet")
                try {
                    // The builder opens and closes one source at a time (#61).
                    val daily = sources.map { source -> DailyParquetSource(source) { ParquetFile.open(archive.reader(source.key)) } }
                    val sink = FileSink(temporary)
                    val pass = try {
                        DayPass.run(station, day, daily, sink, anchors, nowEpochS)
                    } finally {
                        sink.close()
                    }
                    val result = pass.daily
                    if (pass.failedSources.isEmpty()) {
                        tierStore.save(station, day, ArchiveHistoryTiers.signature(sources), TierStore.fromBuckets(pass.buckets))
                    }
                    pass.dailyError?.let { throw it }
                    if (result == null) {
                        remove(manifest)
                        remove(target)
                        continue
                    }
                    // The writer counted the rows, the synced length says every byte reached the file. No footer re-parse.
                    check((fm.attributesOfItemAtPath(temporary, null)?.get(NSFileSize) as? Number)?.toLong() == sink.written) { "derived file is short" }
                    // A crash between the two commits must leave no receipt claiming older bytes.
                    remove(manifest)
                    atomic.commitReplace(temporary, target, StorageClass.Rebuildable)
                    writeManifest(manifest, station, day, result, signature, nowEpochS)
                    paths += target
                    kept += target.substringAfterLast('/')
                } finally {
                    fm.removeItemAtPath(temporary, null)
                }
            }
            val names = fm.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()
            for (name in names) {
                if (!name.startsWith("day=")) continue
                val parquetName = when {
                    name.endsWith(".parquet") -> name
                    name.endsWith(".manifest.json") -> name.removeSuffix(".manifest.json") + ".parquet"
                    else -> continue
                }
                if (parquetName !in kept) remove(checked("$directory/$name"))
            }
            paths
        }
    }

    override suspend fun open(station: String, epochDay: Long, sources: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>): LocalRangeSource? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = checked("$root/${ArchiveLayout.derivedDayPath(station, epochDay)}")
                val cached = cachedSignature(checked("$root/${ArchiveLayout.derivedManifestPath(station, epochDay)}")) ?: return@withLock null
                // Completeness changes the receipt, not the rows, either one is current.
                if (cached != signature(sources, anchors, true) && cached != signature(sources, anchors, false)) return@withLock null
                // The constructor opens its descriptor here, before a rebuild can replace the path.
                if (isFile(target)) LocalRangeSource(LocalFile(target)) else null
            }
        }

    /** The identity of a day's build: the builder, completeness, and every source with the anchor that dates it. */
    private fun signature(sources: List<ArchiveRecord>, anchors: Map<String, TimeEstimate.Anchor>, complete: Boolean): String {
        val text = buildString {
            append("derive-cache-v3:${DailyParquetBuilder.BUILDER}:$complete\n")
            for (source in sources.sortedBy { it.name }) {
                append("${source.name}:${source.bytes}:${source.crc32}:${source.sha256}")
                if (source.hasEstimatedUtc) {
                    val anchor = anchors[source.bootId]
                    append(":est${anchor?.boot}:${anchor?.utcNs}:${anchor?.monoUs}")
                }
                append('\n')
            }
        }
        return ParquetVerify.sha256Hex(text.encodeToByteArray())
    }

    private fun cachedSignature(path: String): String? {
        if (!isFile(path)) return null
        return try {
            LocalFileReader(path).use { reader ->
                if (reader.size > 1_048_576) return null
                Json.parseToJsonElement(reader.read(0, reader.size.toInt()).decodeToString()).jsonObject["source_signature"]?.jsonPrimitive?.content
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeManifest(path: String, station: String, day: Long, result: DailyParquetResult, signature: String, nowEpochS: Long) {
        val json = buildJsonObject {
            put("station", station)
            put("day", ArchiveLayout.dayLabel(day))
            put("rows", result.rows)
            put("columns", buildJsonArray { result.columns.forEach { add(JsonPrimitive(it)) } })
            put("complete", result.complete)
            put("builder", DailyParquetBuilder.BUILDER)
            put("built_at", Instant.fromEpochSeconds(nowEpochS).toString())
            put("source_signature", signature)
            put(
                "sources",
                buildJsonArray {
                    for (source in result.sources) {
                        add(
                            buildJsonObject {
                                put("name", source.record.name)
                                put("bytes", source.record.bytes)
                                put("crc32", source.record.crc32)
                                put("sha256", source.record.sha256)
                                put("rows_used", source.rowsUsed)
                                source.timeAnchor?.let { put("time_anchor", it) }
                            }
                        )
                    }
                }
            )
        }.toString().encodeToByteArray()
        atomic.writeAtomically(path, json, StorageClass.Rebuildable)
    }

    private fun isFile(path: String): Boolean = fm.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeRegular

    private fun remove(path: String) {
        if (fm.attributesOfItemAtPath(path, null) != null) check(fm.removeItemAtPath(path, null)) { "cannot remove stale derived file" }
    }

    private fun checked(path: String): String {
        var current = path
        while (current == root || current.startsWith("$root/")) {
            check(fm.attributesOfItemAtPath(current, null)?.get(NSFileType) != NSFileTypeSymbolicLink) { "refusing a symbolic link in derived files" }
            if (current == root) break
            current = current.substringBeforeLast('/')
        }
        return path
    }

    private class FileSink(path: String) : ParquetSink {
        private val descriptor = open(path, O_WRONLY).also { check(it >= 0) { "cannot open derived temporary file" } }

        /** Bytes handed to the file so far. */
        var written = 0L
            private set

        override fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            written += bytes.size
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).toULong())
                    check(count > 0) { "cannot write derived Parquet" }
                    offset += count.toInt()
                }
            }
        }

        // fsync alone leaves the bytes in the drive cache on Apple hardware. Volumes without F_FULLFSYNC fall back to it.
        override fun flush() {
            if (fcntl(descriptor, F_FULLFSYNC) == 0) return
            val e = errno
            check((e == ENOTSUP || e == EINVAL) && fsync(descriptor) == 0) { "cannot sync derived Parquet" }
        }
        fun close() { check(close(descriptor) == 0) { "cannot close derived Parquet" } }
    }
}

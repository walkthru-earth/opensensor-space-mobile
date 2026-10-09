@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.history.parquet.MappedDataReader
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.sha256Hex
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
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.rename
import platform.posix.write
import kotlin.time.Instant

/**
 * Atomic on-disk daily Parquet cache for iOS. [root] is Application Support; the shared layout
 * puts files in `derived/v1/<station>/day=YYYY-MM-DD.parquet`. Rebuild runs after index repair on
 * startup, after downloads and after durable clock receipts arrive. It needs no network.
 */
class IosDailyDerivedStore(root: String) {
    private val root = root.trimEnd('/')
    private val mutex = Mutex()
    private val fm get() = NSFileManager.defaultManager
    private val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    /** Rebuild changed UTC days, remove obsolete partitions, and return the current daily paths. */
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
            NSURL.fileURLWithPath("$root/${ArchiveLayout.DERIVED}", true).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
            val records = files.filter { it.station == station && it.indexed && it.isTelemetry }
            val days = StationDays.of(records)[station].orEmpty()
            val paths = mutableListOf<String>()
            for (day in days.sorted()) {
                val start = day * StationDays.DAY_NS
                val sources = records.filter { it.onTimeline(start, start + StationDays.DAY_NS) }
                val complete = nowEpochS >= (day + 1) * 86_400 + DailyParquetBuilder.COMPLETE_GRACE_S
                val signatureText = buildString {
                    append("${DailyParquetBuilder.BUILDER}:$complete\n")
                    for (source in sources.sortedBy { it.name }) {
                        append("${source.name}:${source.bytes}:${source.crc32}:${source.sha256}")
                        if (source.hasEstimatedUtc) {
                            val anchor = anchors[source.bootId]
                            append(":est${anchor?.boot}:${anchor?.utcNs}:${anchor?.monoUs}")
                        }
                        append('\n')
                    }
                }
                val signature = ParquetVerify.sha256Hex(signatureText.encodeToByteArray())
                val target = checked("$root/${ArchiveLayout.derivedDayPath(station, day)}")
                val manifest = checked("$root/${ArchiveLayout.derivedManifestPath(station, day)}")
                if (isFile(target) && cachedSignature(manifest) == signature) {
                    paths += target
                    continue
                }
                val temporary = "$target.${NSUUID().UUIDString()}.tmp"
                check(fm.createFileAtPath(temporary, null, protection)) { "cannot create derived temporary file" }
                try {
                    val opened = sources.map { DailyParquetSource(it, ParquetFile.open(archive.reader(it.key))) }
                    val sink = FileSink(temporary)
                    val result = try {
                        DailyParquetBuilder.build(station, day, opened, sink, anchors, nowEpochS)
                    } finally {
                        sink.close()
                    }
                    if (result == null) {
                        remove(manifest)
                        remove(target)
                        continue
                    }
                    // Parse the footer before replacing a previously good cache entry.
                    check(ParquetFile.open(MappedDataReader(temporary)).numRows == result.rows.toLong()) { "derived row count mismatch" }
                    // A crash between the two commits must leave no receipt claiming older bytes.
                    remove(manifest)
                    check(rename(temporary, target) == 0) { "cannot commit derived Parquet" }
                    writeManifest(manifest, station, day, result, signature, nowEpochS)
                    paths += target
                } finally {
                    fm.removeItemAtPath(temporary, null)
                }
            }
            val wanted = paths.map { it.substringAfterLast('/') }.toSet()
            val names = fm.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()
            for (name in names) {
                if (!name.startsWith("day=")) continue
                val parquetName = when {
                    name.endsWith(".parquet") -> name
                    name.endsWith(".manifest.json") -> name.removeSuffix(".manifest.json") + ".parquet"
                    else -> continue
                }
                if (parquetName !in wanted) remove(checked("$directory/$name"))
            }
            paths
        }
    }

    private fun cachedSignature(path: String): String? {
        if (!isFile(path)) return null
        return try {
            val reader = MappedDataReader(path)
            if (reader.size > 1_048_576) return null
            Json.parseToJsonElement(reader.read(0, reader.size.toInt()).decodeToString()).jsonObject["source_signature"]?.jsonPrimitive?.content
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
        json.usePinned {
            val data = NSData.create(bytes = it.addressOf(0), length = json.size.toULong())
            check(data.writeToFile(path, NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication, null)) { "cannot commit derived manifest" }
        }
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
        override fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).toULong())
                    check(count > 0) { "cannot write derived Parquet" }
                    offset += count.toInt()
                }
            }
        }
        override fun flush() { check(fsync(descriptor) == 0) { "cannot sync derived Parquet" } }
        fun close() { close(descriptor) }
    }
}

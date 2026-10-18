package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The one time move of the older iOS index into the shared Room tables (#100). Earlier builds kept one
 * `<hive name>.verified.json` receipt beside each verified file ([ArchiveLayout.LEGACY_RECEIPT_SUFFIX])
 * and the files removed in Settings in `<finalRoot>-pruned/pruned.v1`.
 *
 * [run] reads those files and never writes them. A receipt becomes a record only when its file passes
 * the full check again (size, CRC-32 and `PAR1` against the receipt), so nothing counts as synced
 * without a verify. A receipt that is half written, unreadable or without a valid `size` and `crc` is
 * skipped, and sync downloads that file again. Records go in with [ArchiveIndex.putIfAbsent] and marks
 * with [PrunedFiles.put], so a run cut short and run again changes nothing it already moved.
 *
 * Once everything is in, a Durable [marker] is written, and only then are the old files deleted. With
 * the marker present a later run imports nothing and only deletes any old file still left.
 */
class LegacyReceiptImport(
    private val store: IosArchiveStore,
    /** A file beside the database. Its presence means the import finished. */
    private val marker: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val atomic: AtomicFiles = IosAtomicFiles(IosArchiveStore.tempDirBeside(marker)),
) {
    private val finalRoot = store.finalRoot
    private val prunedRoot = "$finalRoot-pruned"
    private val prunedFile = "$prunedRoot/$PRUNED_FILE"

    /** What [run] moved. Both are zero once the marker is there. */
    data class Result(val records: Int, val pruned: Int)

    suspend fun run(index: ArchiveIndex, pruned: PrunedFiles): Result = withContext(io) {
        val result = if (IosFiles.kind(marker) == Kind.FILE) {
            Result(0, 0)
        } else {
            var records = 0
            for (record in verifiedReceipts()) if (index.putIfAbsent(record)) records++
            val marks = prunedMarks()
            for (mark in marks) pruned.put(mark)
            atomic.writeAtomically(marker, byteArrayOf(), StorageClass.Durable)
            Result(records, marks.size)
        }
        deleteOldFiles()
        result
    }

    private fun receipts(): List<Pair<ArchiveKey, String>> = IosFiles.walk(finalRoot).mapNotNull { relative ->
        if (!relative.endsWith(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)) return@mapNotNull null
        val station = relative.substringBefore('/', missingDelimiterValue = "")
        val name = relative.substringAfter('/').removeSuffix(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)
        if (!ArchiveLayout.isSafeStation(station) || !ArchiveLayout.isArchiveName(name)) return@mapNotNull null
        ArchiveKey(station, name) to "$finalRoot/$relative"
    }

    private fun verifiedReceipts(): List<ArchiveRecord> = receipts().mapNotNull { (key, path) ->
        if (IosFiles.kind(path) != Kind.FILE) return@mapNotNull null
        val text = IosFiles.readSmall(path, RECEIPT_MAX_BYTES)?.decodeToString() ?: return@mapNotNull null
        val record = decode(key.station, key.name, text) ?: return@mapNotNull null
        record.takeIf { verifies(it) }
    }

    private fun verifies(record: ArchiveRecord): Boolean {
        val final = "$finalRoot/${record.station}/${record.name}"
        IosFiles.checkNoLinks(final, finalRoot)
        if (IosFiles.kind(final) != Kind.FILE) return false
        return store.reader(record.key).use { ArchiveVerifier.verify(it, record.bytes, record.crc32) } == ParquetVerify.Result.Ok
    }

    /** The marks of an unreadable file are dropped, as the older build read them as none. */
    private fun prunedMarks(): List<PrunedFile> {
        if (IosFiles.kind(prunedRoot) == Kind.LINK) return emptyList()
        return IosFiles.readSmall(prunedFile, PrunedFilesCodec.MAX_BYTES)?.let(PrunedFilesCodec::decode).orEmpty()
    }

    private fun deleteOldFiles() {
        for ((_, path) in receipts()) if (IosFiles.kind(path) == Kind.FILE) IosFiles.remove(path)
        if (IosFiles.kind(prunedRoot) == Kind.DIRECTORY) {
            if (IosFiles.kind(prunedFile) == Kind.FILE) IosFiles.remove(prunedFile)
            if (IosFiles.walk(prunedRoot).isEmpty()) IosFiles.remove(prunedRoot)
        }
    }

    internal companion object {
        const val PRUNED_FILE = "pruned.v1"
        private const val RECEIPT_MAX_BYTES = 16_384L

        /**
         * The record of one receipt, or null for anything without a valid `size` and `crc`. A receipt
         * from an older index layout with no UTC rows reads as never indexed, so repair indexes it again.
         */
        fun decode(station: String, name: String, text: String): ArchiveRecord? {
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun long(key: String): Long? = o[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
            fun int(key: String): Int? = o[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            fun text(key: String): String? = o[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val size = long("size")?.takeIf { it >= 0 } ?: return null
            val crc = long("crc")?.takeIf { it in 0..0xFFFF_FFFFL } ?: return null
            val record = ArchiveRecord(
                station = station,
                name = name,
                bytes = size,
                crc32 = crc,
                sha256 = text("sha256").orEmpty(),
                syncedAtEpochMs = long("syncedAtEpochMs") ?: 0L,
                sourceDevice = text("sourceDevice").orEmpty(),
                indexedAtEpochMs = long("indexedAtEpochMs"),
                indexError = text("indexError"),
                rows = long("rows"),
                rowGroups = int("rowGroups"),
                schemaVersion = text("schemaVersion"),
                bootId = text("bootId"),
                purpose = text("purpose"),
                utcRows = long("utcRows"),
                firstUtcNs = long("firstUtcNs"),
                lastUtcNs = long("lastUtcNs"),
                firstMonoUs = long("firstMonoUs"),
                lastMonoUs = long("lastMonoUs"),
                firstSeq = long("firstSeq"),
                lastSeq = long("lastSeq"),
                pmValidRows = long("pmValidRows"),
                estFirstUtcNs = long("estFirstUtcNs"),
                estLastUtcNs = long("estLastUtcNs"),
                estAnchorFile = text("estAnchorFile"),
            )
            val stale = (int("indexLayout") ?: 1) < ArchiveRecord.INDEX_LAYOUT && record.needsUtcReindex
            return if (stale) record.copy(indexedAtEpochMs = null) else record
        }
    }
}

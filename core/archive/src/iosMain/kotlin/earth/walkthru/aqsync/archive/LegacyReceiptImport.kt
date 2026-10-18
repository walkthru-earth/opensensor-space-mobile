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
 * It takes two launches, so no old file goes before a later launch has seen its row.
 * - Without the [marker], [run] copies and deletes nothing. A receipt becomes a record only when its
 *   file passes the full check again (size, CRC-32 and `PAR1` against the receipt), so nothing counts as
 *   synced without a verify. A receipt that is half written, unreadable, fails the check or throws is
 *   skipped, and sync verifies or downloads that file again. Records go in with
 *   [ArchiveIndex.putIfAbsent], so an engine record is never replaced. Then `durable` makes the rows
 *   survive a power loss, and only then is the marker written.
 * - With the marker, [run] deletes a receipt whose row is in the index, or whose file no longer passes
 *   the check, as that file is not synced either way. A receipt that passes but has no row (the rows
 *   were lost) goes in again and stays until the next launch sees its row. `pruned.v1` goes once every
 *   mark in it is in [PrunedFiles], otherwise the missing marks go in again and the file stays.
 *
 * A run cut short changes nothing it already moved, and a record already in the index is not verified
 * again. Deletes are best effort, and a file left behind goes on a later launch.
 */
class LegacyReceiptImport(
    private val store: IosArchiveStore,
    /** A file beside the database, written once the first pass's rows are durable. */
    private val marker: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val atomic: AtomicFiles = IosAtomicFiles(IosArchiveStore.tempDirBeside(marker)),
) {
    private val finalRoot = store.finalRoot
    private val prunedRoot = "$finalRoot-pruned"
    private val prunedFile = "$prunedRoot/$PRUNED_FILE"

    /** What [run] put into the ports. */
    data class Result(val records: Int, val pruned: Int)

    /**
     * One pass, as above. [durable] makes every row put so far survive a power loss, for Room a full WAL
     * checkpoint. It runs before the marker is written and before any old file is deleted.
     */
    suspend fun run(index: ArchiveIndex, pruned: PrunedFiles, durable: suspend () -> Unit = {}): Result = withContext(io) {
        if (IosFiles.kind(marker) != Kind.FILE) firstPass(index, pruned, durable) else confirmingPass(index, pruned, durable)
    }

    private suspend fun firstPass(index: ArchiveIndex, pruned: PrunedFiles, durable: suspend () -> Unit): Result {
        var records = 0
        for ((key, path) in receipts()) {
            if (index.get(key) != null) continue
            val record = verified(key, path) ?: continue
            if (index.putIfAbsent(record)) records++
        }
        val marks = prunedMarks()
        for (mark in marks) pruned.put(mark)
        durable()
        atomic.writeAtomically(marker, byteArrayOf(), StorageClass.Durable)
        return Result(records, marks.size)
    }

    private suspend fun confirmingPass(index: ArchiveIndex, pruned: PrunedFiles, durable: suspend () -> Unit): Result {
        var records = 0
        val done = mutableListOf<String>()
        for ((key, path) in receipts()) {
            if (index.get(key) != null) {
                done += path
                continue
            }
            val record = verified(key, path)
            if (record == null) {
                done += path
            } else if (index.putIfAbsent(record)) {
                records++
            }
        }
        val marks = prunedMarks()
        val held = marks.map { it.station }.distinct().flatMap { pruned.forStation(it) }.toSet()
        val missing = marks.filterNot { it in held }
        for (mark in missing) pruned.put(mark)
        durable()
        for (path in done) removeQuietly(path)
        if (missing.isEmpty()) dropMarksFile()
        return Result(records, missing.size)
    }

    /** Deletes `pruned.v1`, for Settings › Storage › Download again, so a later launch cannot bring its marks back. */
    suspend fun dropLegacyMarks() = withContext(io) { dropMarksFile() }

    private fun dropMarksFile() {
        if (IosFiles.kind(prunedRoot) != Kind.DIRECTORY) return
        removeQuietly(prunedFile)
        if (IosFiles.walk(prunedRoot).isEmpty()) removeQuietly(prunedRoot)
    }

    private fun receipts(): List<Pair<ArchiveKey, String>> = IosFiles.walk(finalRoot).mapNotNull { relative ->
        if (!relative.endsWith(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)) return@mapNotNull null
        val station = relative.substringBefore('/', missingDelimiterValue = "")
        val name = relative.substringAfter('/').removeSuffix(ArchiveLayout.LEGACY_RECEIPT_SUFFIX)
        if (!ArchiveLayout.isSafeStation(station) || !ArchiveLayout.isArchiveName(name)) return@mapNotNull null
        ArchiveKey(station, name) to "$finalRoot/$relative"
    }

    /** The receipt's record when its file passes the check, otherwise null, a throw included. */
    private fun verified(key: ArchiveKey, path: String): ArchiveRecord? = try {
        if (IosFiles.kind(path) != Kind.FILE) {
            null
        } else {
            IosFiles.readSmall(path, RECEIPT_MAX_BYTES)?.decodeToString()
                ?.let { decode(key.station, key.name, it) }
                ?.takeIf { verifies(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun verifies(record: ArchiveRecord): Boolean {
        val final = "$finalRoot/${record.station}/${record.name}"
        IosFiles.checkNoLinks(final, finalRoot)
        if (IosFiles.kind(final) != Kind.FILE) return false
        return store.reader(record.key).use { ArchiveVerifier.verify(it, record.bytes, record.crc32) } == ParquetVerify.Result.Ok
    }

    /** The marks of an unreadable file are dropped, as the older build read them as none. */
    private fun prunedMarks(): List<PrunedFile> = try {
        if (IosFiles.kind(prunedRoot) == Kind.LINK) {
            emptyList()
        } else {
            IosFiles.readSmall(prunedFile, PrunedFilesCodec.MAX_BYTES)?.let(PrunedFilesCodec::decode).orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun removeQuietly(path: String) {
        try {
            val kind = IosFiles.kind(path)
            if (kind == Kind.FILE || kind == Kind.DIRECTORY) IosFiles.remove(path)
        } catch (e: Exception) {
            // Left for a later launch.
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

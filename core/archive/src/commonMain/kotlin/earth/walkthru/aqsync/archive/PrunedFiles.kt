package earth.walkthru.aqsync.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * A sensor file the user removed from this phone in Settings (`LegacyFileCleanup`). The planner treats
 * a listed file with the same station, name and size as done, so the next sync does not download it
 * again. The device `FILE` frame has no CRC, so [crc32] is kept for the record only.
 */
data class PrunedFile(
    val station: String,
    val name: String,
    val bytes: Long,
    val crc32: Long,
    val prunedAtEpochMs: Long,
) {
    val key: ArchiveKey get() = ArchiveKey(station, name)
}

/**
 * The phone's record of [PrunedFile]s. It only ever describes files on this phone. Nothing here is
 * sent to a device. Android keeps it in Room, iOS in one small Durable file beside the receipts.
 */
interface PrunedFiles {
    fun observeAll(): Flow<List<PrunedFile>>

    suspend fun forStation(station: String): List<PrunedFile> = observeAll().first().filter { it.station == station }

    /** Adds or replaces the record of [file]. */
    suspend fun put(file: PrunedFile)

    suspend fun remove(key: ArchiveKey)

    /** Forgets every record, so the next sync downloads those files again. Returns how many were cleared. */
    suspend fun clear(): Int

    /** No records, for callers that never prune. */
    object None : PrunedFiles {
        override fun observeAll(): Flow<List<PrunedFile>> = flowOf(emptyList())
        override suspend fun put(file: PrunedFile) = Unit
        override suspend fun remove(key: ArchiveKey) = Unit
        override suspend fun clear(): Int = 0
    }
}

/** The iOS line codec, one record per line. No platform JSON dependency, so common tests cover it. */
internal object PrunedFilesCodec {
    const val VERSION = "pruned-v1"
    const val MAX_BYTES = 8L * 1024 * 1024

    fun encode(files: Collection<PrunedFile>): ByteArray = buildString {
        append(VERSION).append('\n')
        for (f in files.sortedWith(compareBy(PrunedFile::station, PrunedFile::name))) {
            append(f.station).append('\t').append(f.name).append('\t').append(f.bytes).append('\t')
            append(f.crc32).append('\t').append(f.prunedAtEpochMs).append('\n')
        }
    }.encodeToByteArray()

    /** The valid records of [bytes]. A malformed line is skipped, an unknown version reads as empty. */
    fun decode(bytes: ByteArray): List<PrunedFile> {
        val lines = bytes.decodeToString().split('\n')
        if (lines.firstOrNull() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return@mapNotNull null
            val size = parts[2].toLongOrNull() ?: return@mapNotNull null
            val crc = parts[3].toLongOrNull() ?: return@mapNotNull null
            val at = parts[4].toLongOrNull() ?: return@mapNotNull null
            if (!ArchiveLayout.isSafeStation(parts[0]) || !ArchiveLayout.isArchiveName(parts[1]) || size < 0) return@mapNotNull null
            PrunedFile(parts[0], parts[1], size, crc, at)
        }
    }
}

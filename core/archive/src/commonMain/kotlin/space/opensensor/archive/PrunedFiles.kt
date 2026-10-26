package space.opensensor.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * A sensor file the user removed from this phone in Settings (`LegacyFileCleanup`). The planner treats
 * a listed file with the same thing, name and size as done, so the next sync does not download it
 * again. The device `FILE` frame has no CRC, so [crc32] is kept for the record only.
 */
data class PrunedFile(
    val thing: String,
    val name: String,
    val bytes: Long,
    val crc32: Long,
    val prunedAtEpochMs: Long,
) {
    val key: ArchiveKey get() = ArchiveKey(thing, name)
}

/**
 * The phone's record of [PrunedFile]s. It only ever describes files on this phone. Nothing here is
 * sent to a device. Both phones keep it in the Room `pruned_file` table.
 */
interface PrunedFiles {
    fun observeAll(): Flow<List<PrunedFile>>

    suspend fun forThing(thing: String): List<PrunedFile> = observeAll().first().filter { it.thing == thing }

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

/**
 * The line format older iOS builds kept their [PrunedFile]s in, one record per line. Read once by the
 * iOS import into Room (`LegacyReceiptImport`). No platform JSON dependency, so common tests cover it.
 */
internal object PrunedFilesCodec {
    const val VERSION = "pruned-v1"
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * The records of [bytes], or null when the version is not [VERSION] or any line is malformed, so
     * a partly readable file imports no marks.
     */
    fun decode(bytes: ByteArray): List<PrunedFile>? {
        val lines = bytes.decodeToString().split('\n')
        if (lines.first() != VERSION) return null
        return lines.drop(1).filter { it.isNotEmpty() }.map { line ->
            val parts = line.split('\t')
            if (parts.size != 5) return null
            val size = parts[2].toLongOrNull() ?: return null
            val crc = parts[3].toLongOrNull() ?: return null
            val at = parts[4].toLongOrNull() ?: return null
            if (!ArchiveLayout.isSafeThing(parts[0]) || !ArchiveLayout.isArchiveName(parts[1]) || size < 0) return null
            PrunedFile(parts[0], parts[1], size, crc, at)
        }
    }
}

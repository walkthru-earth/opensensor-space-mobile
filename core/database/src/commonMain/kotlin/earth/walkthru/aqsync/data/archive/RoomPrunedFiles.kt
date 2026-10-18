package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.local.database.PrunedFileDao
import earth.walkthru.aqsync.data.local.database.PrunedFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The shared [PrunedFiles] over the `pruned_file` table, on both phones. */
class RoomPrunedFiles(private val dao: PrunedFileDao) : PrunedFiles {
    override fun observeAll(): Flow<List<PrunedFile>> = dao.observeAll().map { rows -> rows.map { it.toPruned() } }

    override suspend fun forStation(station: String): List<PrunedFile> = dao.forStation(station).map { it.toPruned() }

    override suspend fun put(file: PrunedFile) = dao.upsert(PrunedFileEntity(file.station, file.name, file.bytes, file.crc32, file.prunedAtEpochMs))

    override suspend fun remove(key: ArchiveKey) = dao.delete(key.station, key.name)

    override suspend fun clear(): Int = dao.clear()
}

private fun PrunedFileEntity.toPruned() = PrunedFile(station, name, bytes, crc32, prunedAtEpochMs)

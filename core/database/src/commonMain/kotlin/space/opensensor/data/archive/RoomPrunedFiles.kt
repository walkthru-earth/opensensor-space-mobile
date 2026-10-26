package space.opensensor.data.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.local.database.PrunedFileDao
import space.opensensor.data.local.database.PrunedFileEntity

/** The shared [PrunedFiles] over the `pruned_file` table, on both phones. */
class RoomPrunedFiles(private val dao: PrunedFileDao) : PrunedFiles {
    override fun observeAll(): Flow<List<PrunedFile>> = dao.observeAll().map { rows -> rows.map { it.toPruned() } }

    override suspend fun forThing(thing: String): List<PrunedFile> = dao.forThing(thing).map { it.toPruned() }

    override suspend fun put(file: PrunedFile) = dao.upsert(PrunedFileEntity(file.thing, file.name, file.bytes, file.crc32, file.prunedAtEpochMs))

    override suspend fun remove(key: ArchiveKey) = dao.delete(key.thing, key.name)

    override suspend fun clear(): Int = dao.clear()
}

private fun PrunedFileEntity.toPruned() = PrunedFile(thing, name, bytes, crc32, prunedAtEpochMs)

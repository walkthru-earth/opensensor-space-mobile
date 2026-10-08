package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The shared [ArchiveIndex] over Room's `archived_file` table (v10). Queries and ordering are the DAO's. */
@Singleton
class RoomArchiveIndex @Inject constructor(private val dao: ArchivedFileDao) : ArchiveIndex {
    override fun observeAll(): Flow<List<ArchiveRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override fun observeStation(station: String): Flow<List<ArchiveRecord>> = dao.observeForStation(station).map { rows -> rows.map { it.toRecord() } }

    override suspend fun get(key: ArchiveKey): ArchiveRecord? = dao.get(key.station, key.name)?.toRecord()

    override suspend fun put(record: ArchiveRecord) = dao.upsert(record.toEntity())

    override suspend fun remove(key: ArchiveKey) = dao.delete(key.station, key.name)

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = dao.unindexed(limit).map { it.toRecord() }

    override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = dao.withUtcBetween(station, fromNs, toNs).map { it.toRecord() }

    override suspend fun indexedForStation(station: String): List<ArchiveRecord> = dao.indexedForStation(station).map { it.toRecord() }

    override suspend fun stations(): List<String> = dao.stations()
}

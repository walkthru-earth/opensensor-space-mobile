package space.opensensor.data.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.local.database.ArchivedFileDao

/** The shared [ArchiveIndex] over the `archived_file` table, on both phones. Queries and ordering are the DAO's. */
class RoomArchiveIndex(private val dao: ArchivedFileDao) : ArchiveIndex {
    override fun observeAll(): Flow<List<ArchiveRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = dao.observeForThing(thing).map { rows -> rows.map { it.toRecord() } }

    override fun observeNeverIndexed(): Flow<Boolean> = dao.observeAnyNeverIndexed().distinctUntilChanged()

    override fun observeLatestTimed(): Flow<Map<String, Long?>> =
        dao.observeLatestTimed().map { rows -> rows.associate { it.thing to it.lastNs } }.distinctUntilChanged()

    override suspend fun get(key: ArchiveKey): ArchiveRecord? = dao.get(key.thing, key.name)?.toRecord()

    override suspend fun anyIndexedNames(descs: Set<String>, dicts: Set<String>, pinned: Set<String>): Boolean =
        dao.anyIndexedNames(descs.toList(), dicts.toList(), pinned.toList())

    override suspend fun put(record: ArchiveRecord) = dao.upsert(record.toEntity())

    /** One `INSERT OR IGNORE` on the unique (thing, name) index, so a concurrent engine record wins (#60). */
    override suspend fun putIfAbsent(record: ArchiveRecord): Boolean = dao.insertIfAbsent(record.toEntity()) != -1L

    /** Read and write in one Room transaction, not the port's default get then put (#60). */
    override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) =
        dao.update(key.thing, key.name) { row -> transform(row.toRecord()).toEntity(row.id) }

    override suspend fun remove(key: ArchiveKey) = dao.delete(key.thing, key.name)

    override suspend fun unindexed(limit: Int): List<ArchiveRecord> = dao.unindexed(limit).map { it.toRecord() }

    override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = dao.withUtcBetween(thing, fromNs, toNs).map { it.toRecord() }

    override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = dao.indexedForThing(thing).map { it.toRecord() }

    override suspend fun things(): List<String> = dao.things()
}

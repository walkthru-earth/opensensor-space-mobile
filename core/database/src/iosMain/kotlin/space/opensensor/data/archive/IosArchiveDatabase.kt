package space.opensensor.data.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.IosArchiveStore
import space.opensensor.archive.LegacyReceiptImport
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.local.database.AppDatabase
import space.opensensor.data.local.database.checkpointDurably
import space.opensensor.data.local.database.openAppDatabase
import space.opensensor.data.sync.ListCursorState
import space.opensensor.data.sync.ListCursors
import space.opensensor.data.sync.ThingSource
import space.opensensor.data.sync.ThingSources

/**
 * The iOS [index], [pruned] marks, list [cursors] and Thing [sources], the shared Room tables in `<dir>/opensensor.db`. The database opens
 * on first use, and the first call of any port waits for [LegacyReceiptImport] to finish, so no
 * reader sees the index before the older receipts are in. Only the first pass checkpoints the database,
 * before it writes [IMPORT_MARKER]. Once [IMPORT_SETTLED] exists no launch walks the archive or
 * checkpoints. A failed import throws to that caller and runs again on the next call.
 */
class IosArchiveDatabase(store: IosArchiveStore, dir: String) {
    private val database: AppDatabase by lazy { openAppDatabase("$dir/${AppDatabase.FILE_NAME}") }
    private val rooms by lazy { RoomArchiveIndex(database.archivedFileDao()) to RoomPrunedFiles(database.prunedFileDao()) }
    private val import = LegacyReceiptImport(store, "$dir/$IMPORT_MARKER", "$dir/$IMPORT_SETTLED")
    private val imported = MutableStateFlow(false)
    private val importLock = Mutex()

    private suspend fun room(): ArchiveIndex = ready().first

    private suspend fun roomPruned(): PrunedFiles = ready().second

    private suspend fun ready(): Pair<RoomArchiveIndex, RoomPrunedFiles> {
        if (!imported.value) {
            importLock.withLock {
                if (!imported.value) {
                    import.run(rooms.first, rooms.second) { database.checkpointDurably() }
                    imported.value = true
                }
            }
        }
        return rooms
    }

    private fun <T> after(select: (ArchiveIndex) -> Flow<T>): Flow<T> = flow { emitAll(select(room())) }

    val index: ArchiveIndex = object : ArchiveIndex {
        override fun observeAll(): Flow<List<ArchiveRecord>> = after { it.observeAll() }
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = after { it.observeThing(thing) }
        override fun observeNeverIndexed(): Flow<Boolean> = after { it.observeNeverIndexed() }
        override fun observeLatestTimed(): Flow<Map<String, Long?>> = after { it.observeLatestTimed() }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = room().get(key)
        override suspend fun anyIndexedNames(descs: Set<String>, dicts: Set<String>, pinned: Set<String>): Boolean =
            room().anyIndexedNames(descs, dicts, pinned)
        override suspend fun put(record: ArchiveRecord) = room().put(record)
        override suspend fun putIfAbsent(record: ArchiveRecord): Boolean = room().putIfAbsent(record)
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) = room().update(key, transform)
        override suspend fun remove(key: ArchiveKey) = room().remove(key)
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = room().unindexed(limit)
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = room().timeline(thing, fromNs, toNs)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = room().indexedForThing(thing)
        override suspend fun things(): List<String> = room().things()
    }

    val pruned: PrunedFiles = object : PrunedFiles {
        override fun observeAll(): Flow<List<PrunedFile>> = flow { emitAll(roomPruned().observeAll()) }
        override suspend fun forThing(thing: String): List<PrunedFile> = roomPruned().forThing(thing)
        override suspend fun put(file: PrunedFile) = roomPruned().put(file)
        override suspend fun remove(key: ArchiveKey) = roomPruned().remove(key)
        override suspend fun clear(): Int = roomPruned().clear()
    }

    private val roomCursors by lazy { RoomListCursors(database.listCursorDao()) }

    val cursors: ListCursors = object : ListCursors {
        override suspend fun get(thing: String): ListCursorState? = ready().let { roomCursors.get(thing) }
        override suspend fun put(state: ListCursorState) = ready().let { roomCursors.put(state) }
    }

    private val roomSources by lazy { RoomThingSources(database.thingSourceDao()) }

    /** Where each Thing's data comes from, by Thing UUID (#223). */
    val sources: ThingSources = object : ThingSources {
        override suspend fun get(thing: String): ThingSource? = ready().let { roomSources.get(thing) }
        override suspend fun all(): List<ThingSource> = ready().let { roomSources.all() }
        override suspend fun recordIfAbsent(source: ThingSource): Boolean = ready().let { roomSources.recordIfAbsent(source) }
    }

    /** Closes the database, for tests. The app keeps it open for the life of the process. */
    fun close() {
        database.close()
    }

    companion object {
        /**
         * Beside the database. Its presence means the first import pass finished and its rows were
         * checkpointed into the database file, so a later launch deletes the old files.
         */
        const val IMPORT_MARKER = "receipts-imported"

        /** Beside the database. Its presence means every old file is gone and the import does nothing. */
        const val IMPORT_SETTLED = "receipts-settled"
    }
}

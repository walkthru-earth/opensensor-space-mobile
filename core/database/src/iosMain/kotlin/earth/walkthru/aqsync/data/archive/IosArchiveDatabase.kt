package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.LegacyReceiptImport
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.local.database.AppDatabase
import earth.walkthru.aqsync.data.local.database.checkpointDurably
import earth.walkthru.aqsync.data.local.database.openAppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iOS [index] and [pruned] marks, the shared Room tables in `<dir>/aq-sync.db`. The database opens
 * on first use, and the first call of either port waits for [LegacyReceiptImport] to finish, so no
 * reader sees the index before the older receipts are in. Each pass checkpoints the database before it
 * writes the marker or deletes an old file. A failed import throws to that caller and runs again on the
 * next call.
 */
class IosArchiveDatabase(store: IosArchiveStore, dir: String) {
    private val database: AppDatabase by lazy { openAppDatabase("$dir/${AppDatabase.FILE_NAME}") }
    private val rooms by lazy { RoomArchiveIndex(database.archivedFileDao()) to RoomPrunedFiles(database.prunedFileDao()) }
    private val import = LegacyReceiptImport(store, "$dir/$IMPORT_MARKER")
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
        override fun observeStation(station: String): Flow<List<ArchiveRecord>> = after { it.observeStation(station) }
        override fun observeNeverIndexed(): Flow<Boolean> = after { it.observeNeverIndexed() }
        override fun observeLatestTimed(): Flow<Map<String, Long>> = after { it.observeLatestTimed() }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = room().get(key)
        override suspend fun put(record: ArchiveRecord) = room().put(record)
        override suspend fun putIfAbsent(record: ArchiveRecord): Boolean = room().putIfAbsent(record)
        override suspend fun update(key: ArchiveKey, transform: (ArchiveRecord) -> ArchiveRecord) = room().update(key, transform)
        override suspend fun remove(key: ArchiveKey) = room().remove(key)
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = room().unindexed(limit)
        override suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord> = room().timeline(station, fromNs, toNs)
        override suspend fun indexedForStation(station: String): List<ArchiveRecord> = room().indexedForStation(station)
        override suspend fun stations(): List<String> = room().stations()
    }

    val pruned: PrunedFiles = object : PrunedFiles {
        override fun observeAll(): Flow<List<PrunedFile>> = flow { emitAll(roomPruned().observeAll()) }
        override suspend fun forStation(station: String): List<PrunedFile> = roomPruned().forStation(station)
        override suspend fun put(file: PrunedFile) = roomPruned().put(file)
        override suspend fun remove(key: ArchiveKey) = roomPruned().remove(key)
        override suspend fun clear(): Int = roomPruned().clear().also { import.dropLegacyMarks() }
    }

    /** Closes the database, for tests. The app keeps it open for the life of the process. */
    fun close() {
        database.close()
    }

    companion object {
        /**
         * Beside the database. Its presence means the first import pass finished and its rows were
         * checkpointed into the database file. The old files go on a later launch, once their rows are seen.
         */
        const val IMPORT_MARKER = "receipts-imported"
    }
}

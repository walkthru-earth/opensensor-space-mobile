package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.archive.FileArchiveStore
import earth.walkthru.aqsync.data.archive.RoomArchiveIndex
import earth.walkthru.aqsync.data.archive.asRecordIndexer
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The shared [SyncEngine] over Android's adapters, wired like `SyncEngineModule`. File I/O runs on
 * the caller's thread so virtual-time tests keep control of every suspension.
 */
fun androidSyncEngine(
    archive: FileArchive,
    dao: ArchivedFileDao,
    windowRetryBackoffMs: Long = SyncEngine.WINDOW_RETRY_BACKOFF_MS,
    clock: () -> Long = System::currentTimeMillis,
    io: CoroutineDispatcher = Dispatchers.IO,
): SyncEngine = SyncEngine(
    store = FileArchiveStore(archive, Dispatchers.Unconfined),
    index = RoomArchiveIndex(dao),
    indexer = HistoryIndexer(archive, dao, DeviceCoordinator()).asRecordIndexer(archive),
    windowRetryBackoffMs = windowRetryBackoffMs,
    clock = clock,
    io = io,
)

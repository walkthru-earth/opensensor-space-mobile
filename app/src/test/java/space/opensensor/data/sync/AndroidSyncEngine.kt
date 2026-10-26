package space.opensensor.data.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import space.opensensor.data.archive.FileArchiveStore
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.asRecordIndexer
import space.opensensor.data.history.HistoryIndexer
import space.opensensor.data.local.database.ArchivedFileDao

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

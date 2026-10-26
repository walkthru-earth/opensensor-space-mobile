package space.opensensor.data.archive

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.coroutines.WallClock
import space.opensensor.data.history.HistoryIndexer
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.sync.ArchiveCollector
import space.opensensor.data.sync.ArchiveRecordIndexer
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileArchive
import space.opensensor.data.sync.SyncEngine
import space.opensensor.data.toIdentity
import space.opensensor.storage.StorageBudget
import javax.inject.Singleton

/**
 * Android's shared [SyncEngine]: the `filesDir` store, the Room index, and the History indexer that
 * fills the index columns in the same upsert as the receipt.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncEngineModule {
    @Provides
    @Singleton
    fun provideArchiveCollector(
        store: ArchiveStore,
        index: ArchiveIndex,
        engine: SyncEngine,
        budget: StorageBudget,
        files: ArchivedFileDao,
    ): ArchiveCollector = ArchiveCollector(
        store,
        index,
        engine,
        budget,
        archivedIdentities = { station -> files.identitiesForStation(station).map { it.toIdentity() } },
    )

    /**
     * The archive's one coordinator, shared by sync, the index repair, the storage janitor and the old
     * file cleanup, so they all see the same admission, writer leases, station gates and tombstones.
     */
    @Provides
    @Singleton
    fun provideDeviceCoordinator(pruned: PrunedFiles): DeviceCoordinator = DeviceCoordinator(pruned)

    @Provides
    @Singleton
    fun provideSyncEngine(
        store: ArchiveStore,
        index: ArchiveIndex,
        archive: FileArchive,
        historyIndexer: HistoryIndexer,
        clock: WallClock,
        coordinator: DeviceCoordinator,
    ): SyncEngine = SyncEngine(store, index, historyIndexer.asRecordIndexer(archive), clock = clock::nowEpochMs, coordinator = coordinator)
}

/** Indexes the committed copy in `filesDir`, the file [HistoryIndexer] reads. */
fun HistoryIndexer.asRecordIndexer(archive: FileArchive): ArchiveRecordIndexer = ArchiveRecordIndexer { record ->
    indexed(record.toEntity(), archive.fileFor(record.station, record.name)).toRecord()
}

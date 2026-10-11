package earth.walkthru.aqsync.data.archive

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.coroutines.WallClock
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.ArchiveRecordIndexer
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.data.toIdentity
import earth.walkthru.aqsync.storage.StorageBudget
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

    /** One writer per archive file, shared by the engine and the storage janitor's partial sweep. */
    @Provides
    @Singleton
    fun provideWriterLeases(): WriterLeases = WriterLeases()

    @Provides
    @Singleton
    fun provideSyncEngine(
        store: ArchiveStore,
        index: ArchiveIndex,
        archive: FileArchive,
        historyIndexer: HistoryIndexer,
        clock: WallClock,
        leases: WriterLeases,
    ): SyncEngine = SyncEngine(store, index, historyIndexer.asRecordIndexer(archive), leases = leases, clock = clock::nowEpochMs)
}

/** Indexes the committed copy in `filesDir`, the file [HistoryIndexer] reads. */
fun HistoryIndexer.asRecordIndexer(archive: FileArchive): ArchiveRecordIndexer = ArchiveRecordIndexer { record ->
    indexed(record.toEntity(), archive.fileFor(record.station, record.name)).toRecord()
}

package earth.walkthru.aqsync.data.storage

import android.content.Context
import android.os.storage.StorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.data.archive.asRecordIndexer
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.coroutines.WallClock
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.JvmStorageTree
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageJanitor
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

/** Android's storage layer of #60. The roots are today's locations, moving Rebuildable data is #61. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideStorageRoots(@ApplicationContext context: Context): StorageRoots {
        val files = context.filesDir.absoluteFile
        return StorageRoots(
            mapOf(
                StorageRole.Archive to File(files, ArchiveLayout.ARCHIVE).path,
                StorageRole.Index to context.getDatabasePath("aq-sync.db").absoluteFile.parentFile!!.path,
                StorageRole.ClockAnchors to File(files, "clock-anchors").path,
                StorageRole.Derived to File(files, ArchiveLayout.DERIVED).path,
                StorageRole.Partials to File(files, ArchiveLayout.PARTIAL).path,
                StorageRole.Temp to File(files, AtomicFiles.TEMP_DIR).path,
                StorageRole.Exports to File(context.cacheDir.absoluteFile, "export").path,
                StorageRole.Settings to File(files, "datastore").path,
            ),
        )
    }

    @Provides
    @Singleton
    fun provideAtomicFiles(roots: StorageRoots): AtomicFiles = JvmAtomicFiles(File(roots.rootOf(StorageRole.Temp)))

    @Provides
    fun provideStorageTree(): StorageTree = JvmStorageTree()

    @Provides
    @Singleton
    fun provideStorageBudget(@ApplicationContext context: Context, roots: StorageRoots, @IoDispatcher io: CoroutineDispatcher): StorageBudget =
        AndroidStorageBudget(context.getSystemService(StorageManager::class.java), roots, io)

    @Provides
    fun provideStorageJanitor(
        roots: StorageRoots,
        tree: StorageTree,
        store: ArchiveStore,
        index: ArchiveIndex,
        archive: FileArchive,
        historyIndexer: HistoryIndexer,
        knownDevices: KnownDeviceDao,
        clock: WallClock,
        @IoDispatcher io: CoroutineDispatcher,
    ): StorageJanitor = StorageJanitor(
        roots = roots,
        tree = tree,
        store = store,
        index = index,
        io = io,
        indexer = historyIndexer.asRecordIndexer(archive),
        knownStations = { knownDevices.allByLastConnected().mapNotNull { it.station } },
        clock = clock::nowEpochMs,
    )
}

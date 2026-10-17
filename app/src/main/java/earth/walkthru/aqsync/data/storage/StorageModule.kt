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
import earth.walkthru.aqsync.archive.PrunedFiles
import earth.walkthru.aqsync.data.archive.asRecordIndexer
import earth.walkthru.aqsync.data.coroutines.IoDispatcher
import earth.walkthru.aqsync.data.coroutines.WallClock
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.sync.ArchiveMutationGate
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.net.BlockCache
import earth.walkthru.aqsync.net.DiskBlockStore
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.DerivedQuota
import earth.walkthru.aqsync.storage.JvmAtomicFiles
import earth.walkthru.aqsync.storage.JvmStorageTree
import earth.walkthru.aqsync.storage.QuotaEnforcer
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageJanitor
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.StorageTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Singleton

/** Android's storage layer of #60. Rebuildable data lives in `cacheDir` (#61). */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideStorageRoots(@ApplicationContext context: Context): StorageRoots {
        val files = context.filesDir.absoluteFile
        val cache = context.cacheDir.absoluteFile
        return StorageRoots(
            mapOf(
                StorageRole.Archive to File(files, ArchiveLayout.ARCHIVE).path,
                StorageRole.Index to context.getDatabasePath("aq-sync.db").absoluteFile.parentFile!!.path,
                StorageRole.ClockAnchors to File(files, "clock-anchors").path,
                StorageRole.Derived to File(cache, ArchiveLayout.DERIVED).path,
                StorageRole.LegacyDerived to File(files, "derived").path,
                StorageRole.Partials to File(files, ArchiveLayout.PARTIAL).path,
                StorageRole.Temp to File(files, AtomicFiles.TEMP_DIR).path,
                StorageRole.Exports to File(cache, "export").path,
                StorageRole.FeedBlocks to File(cache, "feed-blocks").path,
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

    /**
     * The derived quota is the stored policy's, read at each enforcement so a saved change applies
     * without a restart, and capped by what Android grants this app's cache
     * (`getCacheQuotaBytes`) less the map cache, so the derived files are not the first thing the
     * system clears under pressure.
     */
    @Provides
    @Singleton
    fun provideDerivedQuota(
        @ApplicationContext context: Context,
        roots: StorageRoots,
        tree: StorageTree,
        settings: SettingsRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ): DerivedQuota {
        val storage = context.getSystemService(StorageManager::class.java)
        val cache = context.cacheDir.absoluteFile
        return DerivedQuota(QuotaEnforcer(roots, tree), io) {
            val policy = settings.current().retention
            withContext(io) {
                try {
                    policy.derivedQuotaWithin(storage.getCacheQuotaBytes(storage.getUuidForPath(cache)))
                } catch (e: IOException) {
                    policy.derivedQuotaBytes
                }
            }
        }
    }

    /**
     * The disk block cache of the public feed (#94). Its quota is the stored policy's, capped by what
     * Android grants this app's cache less the map cache, and 0 while the volume runs low, so the cache
     * gives its files back under storage pressure.
     */
    @Provides
    @Singleton
    fun provideBlockCache(
        @ApplicationContext context: Context,
        roots: StorageRoots,
        atomic: AtomicFiles,
        tree: StorageTree,
        budget: StorageBudget,
        settings: SettingsRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ): BlockCache {
        val storage = context.getSystemService(StorageManager::class.java)
        val cache = context.cacheDir.absoluteFile
        val store = DiskBlockStore(roots.rootOf(StorageRole.FeedBlocks), atomic, tree)
        return BlockCache(store, io = io, quotaBytes = {
            val policy = settings.current().retention
            withContext(io) {
                val allowance = try {
                    storage.getCacheQuotaBytes(storage.getUuidForPath(cache))
                } catch (e: IOException) {
                    Long.MAX_VALUE
                }
                policy.blockCacheQuotaWithin(allowance, budget.freeBytes(StorageClass.Rebuildable))
            }
        })
    }

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
        quota: DerivedQuota,
        leases: WriterLeases,
        gate: ArchiveMutationGate,
        pruned: PrunedFiles,
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
        quota = quota,
        leases = leases,
        gate = gate,
        pruned = pruned,
    )
}

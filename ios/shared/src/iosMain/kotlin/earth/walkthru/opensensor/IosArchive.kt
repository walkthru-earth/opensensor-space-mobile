@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ClockAnchor
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.IosClockAnchorStore
import earth.walkthru.aqsync.archive.IosExportSpace
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.data.history.ArchiveHistoryTiers
import earth.walkthru.aqsync.data.history.ArchiveIndexRepair
import earth.walkthru.aqsync.data.history.HistorySeriesReader
import earth.walkthru.aqsync.data.history.IosDailyDerivedStore
import earth.walkthru.aqsync.data.history.IosFileTierStore
import earth.walkthru.aqsync.data.history.TimeEstimate
import earth.walkthru.aqsync.data.history.info
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.DerivedQuota
import earth.walkthru.aqsync.storage.IosAtomicFiles
import earth.walkthru.aqsync.storage.IosStorageBudget
import earth.walkthru.aqsync.storage.IosStorageTree
import earth.walkthru.aqsync.storage.QuotaEnforcer
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageClass
import earth.walkthru.aqsync.storage.StorageJanitor
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.aqsync.storage.trimOnMemoryPressure
import earth.walkthru.opensensor.discover.MapCache
import earth.walkthru.opensensor.settings.StorageControls
import earth.walkthru.opensensor.settings.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

/**
 * The one phone archive of the iOS process: the store, its receipt index, the in-memory History
 * tiers and the History reader. The sync runner and History share the same index instance, so a file
 * saved by a sync shows in History without a relaunch.
 */
internal object IosArchive {
    val store: IosArchiveStore by lazy { IosArchiveStore.inApplicationSupport() }
    val index: ArchiveIndex by lazy { ReceiptArchiveIndex(store.finalRoot) }
    private val clockAnchorsRoot get() = store.finalRoot + "-clock-anchors"
    val clockAnchors by lazy { IosClockAnchorStore(clockAnchorsRoot) }
    // Tiers persist beside the derived day files, so a cold History open decodes only new days (#63).
    val tiers: ArchiveHistoryTiers by lazy {
        ArchiveHistoryTiers(store, index, clockAnchors = clockAnchors, tierStore = IosFileTierStore(cachesDirectory(), derivedAtomic), shareScope = scope)
    }
    val reader: HistorySeriesReader by lazy { HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors, derived = derived) }

    /** One writer per archive file, for the sync runner and the janitor's partial sweep. */
    val leases = WriterLeases()

    private val derivedMutex = Mutex()
    private val base get() = store.finalRoot.substringBeforeLast('/')

    /**
     * Rebuildable data lives in `Library/Caches` (#61). Builds before #61 kept `derived/v1` in
     * Application Support, which the janitor removes whole ([StorageRole.LegacyDerived]).
     */
    val roots: StorageRoots by lazy {
        StorageRoots(
            mapOf(
                StorageRole.Archive to store.finalRoot,
                StorageRole.Partials to store.partialRoot,
                StorageRole.ClockAnchors to clockAnchorsRoot,
                StorageRole.Derived to "${cachesDirectory()}/${ArchiveLayout.DERIVED}",
                StorageRole.LegacyDerived to "$base/derived",
                StorageRole.Temp to "$base/${AtomicFiles.TEMP_DIR}",
                StorageRole.Exports to IosExportSpace.temporaryRoot(),
            ),
        )
    }

    /** Free space per class, asked before a sync opens any file (#55). */
    val budget: StorageBudget by lazy { IosStorageBudget(roots) }

    /** iOS has no per app cache quota call, so the derived quota is the policy's. */
    val derivedQuota: DerivedQuota by lazy { DerivedQuota(QuotaEnforcer(roots, IosStorageTree(), log = ::println)) }

    /** The Settings Storage card. Clearing touches the derived day files and the map tiles only (#61). */
    val storageControls: StorageControls by lazy {
        object : StorageControls {
            override suspend fun usage(): StorageUsage {
                val used = budget.usage()
                return StorageUsage(
                    sensorFilesBytes = used[StorageClass.Durable] ?: 0L,
                    historyCacheBytes = used[StorageClass.Rebuildable] ?: 0L,
                    mapCacheBytes = withContext(Dispatchers.IO) { MapCache.bytes() },
                    temporaryBytes = used[StorageClass.Scratch] ?: 0L,
                )
            }

            override suspend fun clearHistoryCache() {
                derivedMutex.withLock { withContext(Dispatchers.IO) { derivedQuota.clear() } }
            }

            override suspend fun clearMapCache() = MapCache.clear()
        }
    }

    // Temps stay in Application Support, on the same volume as Library/Caches, where the janitor sweeps them.
    private val derivedAtomic by lazy { IosAtomicFiles("$base/${AtomicFiles.TEMP_DIR}") }
    private val derived by lazy { IosDailyDerivedStore(cachesDirectory(), derivedAtomic) }
    private val repair by lazy { ArchiveIndexRepair(store, index, clockAnchors = clockAnchors) }
    private val janitor by lazy {
        StorageJanitor(roots, IosStorageTree(), store, index, Dispatchers.IO, log = ::println, quota = derivedQuota, leases = leases)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    suspend fun recordClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {
        clockAnchors.put(ClockAnchor(station, boot, utcNs, monotonicUs))
        repair.estimateTimes()
        rebuildDerived()
    }

    private suspend fun rebuildDerived() = derivedMutex.withLock {
        val records = index.observeAll().first()
        for ((station, files) in records.groupBy { it.station }) {
            val anchors = TimeEstimate.anchors(files.filter { it.indexed }.map { it.info() }, clockAnchors.anchors(station))
            derived.rebuild(station, files, store, anchors, Clock.System.now().epochSeconds)
        }
        withContext(Dispatchers.IO) { derivedQuota.enforce() }
    }

    /**
     * History index repair (F39): at start, and again whenever a record is waiting for its index,
     * fills the index columns of every verified file from its Parquet footer. It only reads files.
     * Call on the main thread. Safe to call more than once, the first call starts it.
     */
    fun startIndexRepair() {
        if (started) return
        started = true
        IosMemoryPressure.install()
        // The tiers give memory back on a memory warning, the next History pass rebuilds them (#61).
        scope.trimOnMemoryPressure(tiers)
        scope.launch {
            // The startup sweep of #60 first, so an orphan final it adopts is indexed by the repair below.
            try {
                janitor.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("storage janitor failed: ${e::class.simpleName}")
            }
            repair.backfillUntilSettled()
            repair.repairWhenWaiting()
        }
        scope.launch {
            index.observeAll().conflate().collect {
                try {
                    rebuildDerived()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Originals and receipts survive; the next archive change or process start retries.
                    println("derived history retry needed: ${e::class.simpleName}")
                }
            }
        }
    }
}

/**
 * The sync runner over the process archive, so History sees what it saves. Swift calls this once
 * (`IosArchiveKt.sharedSensorSync()`), which also starts the History index repair.
 */
fun sharedSensorSync(): SensorSync {
    IosArchive.startIndexRepair()
    return SensorSync(IosArchive.store, IosArchive.index, Dispatchers.Default, Dispatchers.Main, recordClockAnchor = IosArchive::recordClockAnchor, budget = IosArchive.budget, leases = IosArchive.leases)
}

/** `Library/Caches` of this app. */
internal fun cachesDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
    return checkNotNull(url?.path) { "Library/Caches is unavailable" }
}

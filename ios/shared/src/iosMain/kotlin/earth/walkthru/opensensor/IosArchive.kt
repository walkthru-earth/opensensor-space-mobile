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
import earth.walkthru.aqsync.data.history.LegacyFileCleanup
import earth.walkthru.aqsync.data.history.TimeEstimate
import earth.walkthru.aqsync.data.history.UtcDayRange
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
import earth.walkthru.opensensor.settings.OldFilesPlan
import earth.walkthru.opensensor.settings.OldFilesRange
import earth.walkthru.opensensor.settings.OldFilesResult
import earth.walkthru.opensensor.settings.StorageControls
import earth.walkthru.opensensor.settings.StorageUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlin.concurrent.AtomicReference
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
        ArchiveHistoryTiers(
            store,
            index,
            clockAnchors = clockAnchors,
            tierStore = tierStore,
            shareScope = scope,
            beforeBuild = { station, files, anchors ->
                derivedMutex.withLock {
                    try {
                        derived.rebuild(station, files, store, anchors, Clock.System.now().epochSeconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The pass may have saved tiers even when the daily cache could not commit.
                        println("derived history retry needed: ${e::class.simpleName}")
                    }
                }
            },
            afterBuild = {
                derivedMutex.withLock {
                    try {
                        withContext(Dispatchers.IO) { derivedQuota.enforce() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("derived quota retry needed: ${e::class.simpleName}")
                    }
                }
            },
        )
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

    /**
     * iOS has no per app cache quota call, so the derived quota is the stored policy's, read at each
     * enforcement.
     */
    val derivedQuota: DerivedQuota by lazy {
        DerivedQuota(QuotaEnforcer(roots, IosStorageTree(), log = ::println)) { settingsRepository.current().retention.derivedQuotaBytes }
    }

    /**
     * The Settings Storage card. Clearing touches the derived day files and the map tiles only (#61).
     * Old format sensor files leave the phone only through [oldFiles], after the user confirms.
     */
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

            override fun syncRunning(): Boolean = sync.value?.isBusy() == true

            override suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan {
                val plan = oldFiles.plan(range?.let { UtcDayRange(it.fromEpochDay, it.toEpochDay) })
                return OldFilesPlan(plan.files, plan.bytes, plan.stations, range, skipped = plan.skipped, handle = plan)
            }

            override suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult {
                val planned = plan.handle as? LegacyFileCleanup.Plan ?: return OldFilesResult(0, 0, plan.files, syncRunning = false)
                val report = oldFiles.remove(planned)
                return OldFilesResult(report.removed, report.bytes, report.kept, report.stoppedBySync, report.failed, report.rebuildFailed)
            }
        }
    }

    /** The process sync runner once Swift asked for it, so Settings can tell whether a sync runs. */
    val sync = AtomicReference<SensorSync?>(null)

    /**
     * The phone only cleanup of old format files behind Settings. The receipt index change already
     * rebuilds the derived days through [startIndexRepair], the explicit rebuild runs it now and also
     * visits the stations whose files all went, so their stale day files are removed.
     */
    private val oldFiles by lazy {
        LegacyFileCleanup(
            store = store,
            index = index,
            roots = roots,
            tree = IosStorageTree(),
            leases = leases,
            io = Dispatchers.IO,
            syncRunning = { sync.value?.isBusy() == true },
            afterRemoval = { days -> rebuildDerived(days.keys) },
            log = ::println,
        )
    }

    // Temps stay in Application Support, on the same volume as Library/Caches, where the janitor sweeps them.
    private val derivedAtomic by lazy { IosAtomicFiles("$base/${AtomicFiles.TEMP_DIR}") }
    private val tierStore by lazy { IosFileTierStore(cachesDirectory(), derivedAtomic) }
    private val derived by lazy { IosDailyDerivedStore(cachesDirectory(), derivedAtomic, tierStore) }
    private val repair by lazy { ArchiveIndexRepair(store, index, clockAnchors = clockAnchors) }
    private val janitor by lazy {
        StorageJanitor(roots, IosStorageTree(), store, index, Dispatchers.IO, log = ::println, quota = derivedQuota, leases = leases)
    }

    /**
     * A failure that escapes a launch here would end the process on Kotlin/Native, so the scope logs
     * the class name only. Each loop catches its own errors first, and this is the last guard.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> println("archive task ended: ${e::class.simpleName}") },
    )
    private var started = false

    /**
     * Estimating times opens every indexed footer and the rebuild touches every station, so both run
     * here, never under the sync lease that recorded the anchor. A first sync starts downloading at once.
     */
    private val anchorFollowUp by lazy {
        CoalescedWork(scope, "clock anchor estimate") {
            repair.estimateTimes()
            rebuildDerived()
        }
    }

    /** Called under the SYNC or COMMAND lease. Only the anchor is saved there, the rest follows later. */
    suspend fun recordClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {
        clockAnchors.put(ClockAnchor(station, boot, utcNs, monotonicUs))
        anchorFollowUp.request()
    }

    /**
     * Rebuilds the derived days of every station with records, and of each of [emptied]. A station in
     * [emptied] that has no records left is rebuilt from no sources, which removes its stale day files.
     */
    private suspend fun rebuildDerived(emptied: Collection<String> = emptyList()) = derivedMutex.withLock {
        val byStation = index.observeAll().first().groupBy { it.station }
        for (station in byStation.keys + emptied) {
            val files = byStation[station].orEmpty()
            val anchors = TimeEstimate.anchors(files.filter { it.isIndexed }.map { it.info() }, clockAnchors.anchors(station))
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
            try {
                repair.repairWhenWaiting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The index flow failed. The next process start repairs again.
                println("index repair stopped: ${e::class.simpleName}")
            }
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
 * (`IosArchiveKt.sharedSensorSync()`), which also starts the History index repair. Every call returns
 * the same runner, which Settings asks whether a sync runs.
 */
fun sharedSensorSync(): SensorSync {
    IosArchive.startIndexRepair()
    IosArchive.sync.value?.let { return it }
    val created = SensorSync(IosArchive.store, IosArchive.index, Dispatchers.Default, Dispatchers.Main, recordClockAnchor = IosArchive::recordClockAnchor, budget = IosArchive.budget, leases = IosArchive.leases)
    return if (IosArchive.sync.compareAndSet(null, created)) created else IosArchive.sync.value!!
}

/** `Library/Caches` of this app. */
internal fun cachesDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
    return checkNotNull(url?.path) { "Library/Caches is unavailable" }
}

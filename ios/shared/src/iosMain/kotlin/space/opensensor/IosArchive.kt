@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor

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
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ClockAnchor
import space.opensensor.archive.IosArchiveStore
import space.opensensor.archive.IosClockAnchorStore
import space.opensensor.archive.IosExportSpace
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.archive.IosArchiveDatabase
import space.opensensor.data.history.ArchiveHistoryTiers
import space.opensensor.data.history.ArchiveIndexRepair
import space.opensensor.data.history.BoardDescriptors
import space.opensensor.data.history.HistoryOrchestrator
import space.opensensor.data.history.HistorySeriesReader
import space.opensensor.data.history.HistoryStartup
import space.opensensor.data.history.IosDailyDerivedStore
import space.opensensor.data.history.IosFileTierStore
import space.opensensor.data.history.LegacyFileCleanup
import space.opensensor.data.history.TemplateRederive
import space.opensensor.data.history.TimeEstimate
import space.opensensor.data.history.UtcDayRange
import space.opensensor.data.history.info
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.ListCursors
import space.opensensor.data.sync.ThingPlaces
import space.opensensor.data.sync.ThingSources
import space.opensensor.discover.MapCache
import space.opensensor.net.BlockCache
import space.opensensor.net.DiskBlockStore
import space.opensensor.settings.OldFilesPlan
import space.opensensor.settings.OldFilesRange
import space.opensensor.settings.OldFilesResult
import space.opensensor.settings.StorageControls
import space.opensensor.settings.StorageUsage
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.DerivedQuota
import space.opensensor.storage.IosAtomicFiles
import space.opensensor.storage.IosStorageBudget
import space.opensensor.storage.IosStorageTree
import space.opensensor.storage.QuotaEnforcer
import space.opensensor.storage.StorageBudget
import space.opensensor.storage.StorageClass
import space.opensensor.storage.StorageJanitor
import space.opensensor.storage.StorageRole
import space.opensensor.storage.StorageRoots
import space.opensensor.storage.trimOnMemoryPressure
import kotlin.time.Clock

/**
 * The one phone archive of the iOS process: the store, its Room index, the in-memory History
 * tiers and the History reader. The sync runner and History share the same index instance, so a file
 * saved by a sync shows in History without a relaunch.
 */
internal object IosArchive {
    val store: IosArchiveStore by lazy { IosArchiveStore.inApplicationSupport() }

    /** The shared Room tables in `Application Support/database/opensensor.db`, after the one time receipt import. */
    private val database by lazy { IosArchiveDatabase(store, "$base/database") }
    val index: ArchiveIndex get() = database.index
    private val clockAnchorsRoot get() = store.finalRoot + "-clock-anchors"
    val clockAnchors by lazy { IosClockAnchorStore(clockAnchorsRoot) }

    /** Files removed in Settings, which sync skips. Kept outside the thing directories. */
    val pruned: PrunedFiles get() = database.pruned

    /** Where each station's incremental list left off, beside the index. */
    val cursors: ListCursors get() = database.cursors

    /** Where each Thing's data comes from, by Thing UUID, beside the index. */
    val sources: ThingSources get() = database.sources

    /** Each own fixed Thing's saved cell, for its pin on Discover (#226). Only the cell, never coordinates. */
    val places: ThingPlaces get() = database.places

    /** Drops the saved cells of [things] once their sensor is forgotten, so none stays on the phone. */
    suspend fun forgetPlaces(things: List<String>) {
        things.forEach { places.remove(it) }
    }

    // Tiers persist beside the derived day files, so a cold History open decodes only new days (#63).
    val tiers: ArchiveHistoryTiers by lazy {
        ArchiveHistoryTiers(
            store,
            index,
            clockAnchors = clockAnchors,
            tierStore = tierStore,
            shareScope = scope,
            rederive = templateRederive,
            beforeBuild = { thing, files, anchors ->
                derivedMutex.withLock {
                    try {
                        derived.rebuild(thing, files, store, anchors, Clock.System.now().epochSeconds)
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

    /**
     * The archive's one coordinator, shared by sync, the janitor, the index repair and [oldFiles]. It
     * admits one sync per thing and holds the writer leases, thing gates and tombstones.
     */
    val coordinator: DeviceCoordinator by lazy { DeviceCoordinator(pruned) }

    /**
     * The shared order of the History passes (#100), the backfill then [rebuildDerived]. It owns the
     * index repair, so no backfill or time estimate here runs outside a pass.
     */
    private val history by lazy {
        HistoryOrchestrator(ArchiveIndexRepair(store, index, clockAnchors = clockAnchors, coordinator = coordinator, onTemplateDigest = boardDescriptors::confirm), index, log = ::println)
    }

    /**
     * The shared History start (#125). The janitor and the feed block quota first, then the backfill,
     * then [rebuildDerived], and again whenever a record waits for its index. [startIndexRepair]
     * starts it once.
     */
    val historyStartup by lazy { HistoryStartup(history, janitor, feedBlocks, log = ::println, rederive = templateRederive) }

    /** The one template gate of the process, shared by [tiers] and [historyStartup]. */
    private val templateRederive by lazy { TemplateRederive(index, log = ::println) }

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
                StorageRole.FeedBlocks to "${cachesDirectory()}/feed-blocks",
                StorageRole.Templates to "${cachesDirectory()}/board-templates",
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

    /** Board templates sensors served over `GET_DESCRIPTOR` (#191), in `Library/Caches`, keyed by SHA-256. */
    val boardDescriptors: BoardDescriptors by lazy {
        BoardDescriptors(DiskBlockStore(roots.rootOf(StorageRole.Templates), derivedAtomic, IosStorageTree()))
    }

    /**
     * The disk block cache of the public feed (#94), in `Library/Caches` where iOS purges it under
     * storage pressure. iOS has no per app cache quota call, so the quota is the stored policy's, and
     * 0 while the volume runs low. [startIndexRepair] applies it once at start.
     */
    val feedBlocks: BlockCache by lazy {
        val store = DiskBlockStore(roots.rootOf(StorageRole.FeedBlocks), derivedAtomic, IosStorageTree())
        BlockCache(store, quotaBytes = {
            settingsRepository.current().retention.blockCacheQuotaWithin(Long.MAX_VALUE, budget.freeBytes(StorageClass.Rebuildable))
        })
    }

    /**
     * The Settings Storage card. Clearing touches the derived day files, the feed blocks and the map
     * tiles only (#61). Old format sensor files leave the phone only through [oldFiles], after the user
     * confirms. Sync skips them until the user chooses Download again.
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
                feedBlocks.clear()
            }

            override suspend fun clearMapCache() = MapCache.clear()

            override suspend fun archiveDays(): OldFilesRange? = oldFiles.archiveDays()?.let { OldFilesRange(it.fromEpochDay, it.toEpochDay) }

            override suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan {
                val plan = oldFiles.plan(range?.let { UtcDayRange(it.fromEpochDay, it.toEpochDay) })
                return OldFilesPlan(plan.files, plan.bytes, plan.things, range, skipped = plan.skipped, handle = plan)
            }

            override suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult {
                val planned = plan.handle as? LegacyFileCleanup.Plan ?: return OldFilesResult(0, 0, plan.files)
                val report = oldFiles.remove(planned)
                return OldFilesResult(report.removed, report.bytes, report.kept, report.failed, report.rebuildFailed)
            }

            override suspend fun removedFiles(): Int = pruned.observeAll().first().size

            override suspend fun downloadRemovedAgain() {
                pruned.clear()
            }
        }
    }

    /**
     * The phone only cleanup of old format files behind Settings. The index change already
     * rebuilds the derived days through [startIndexRepair], the explicit rebuild runs it now and also
     * visits the things whose files all went, so their stale day files are removed.
     */
    private val oldFiles by lazy {
        LegacyFileCleanup(
            store = store,
            index = index,
            roots = roots,
            tree = IosStorageTree(),
            coordinator = coordinator,
            io = Dispatchers.IO,
            afterRemoval = { days -> rebuildDerived(days.keys) },
            log = ::println,
        )
    }

    // Temps stay in Application Support, on the same volume as Library/Caches, where the janitor sweeps them.
    private val derivedAtomic by lazy { IosAtomicFiles("$base/${AtomicFiles.TEMP_DIR}") }
    private val tierStore by lazy { IosFileTierStore(cachesDirectory(), derivedAtomic) }
    private val derived by lazy { IosDailyDerivedStore(cachesDirectory(), derivedAtomic, tierStore) }
    private val janitor by lazy {
        StorageJanitor(roots, IosStorageTree(), store, index, Dispatchers.IO, log = ::println, quota = derivedQuota, coordinator = coordinator)
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
     * Estimating times opens every indexed footer and the rebuild touches every thing, so both run
     * here, never under the sync lease that recorded the anchor. A first sync starts downloading at once.
     */
    private val anchorFollowUp by lazy { clockAnchorFollowUp(scope, history) { rebuildDerived(emptyList()) } }

    /** Called under the SYNC or COMMAND lease. Only the anchor is saved there, the rest follows later. */
    suspend fun recordClockAnchor(thing: String, boot: String, utcNs: Long, monotonicUs: Long) {
        clockAnchors.put(ClockAnchor(thing, boot, utcNs, monotonicUs))
        anchorFollowUp.request()
    }

    /**
     * Rebuilds the derived days of every thing with records, and of each of the emptied things it
     * is given. An emptied thing with no records left is rebuilt from no sources, which removes its
     * stale day files. It is the one derive entry point, built by [HistoryOrchestrator.derivePassOf],
     * so every call is one pass and never runs beside a backfill. The History reader's own refresh of
     * one thing takes [derivedMutex] only, so History stays visible during a backfill and keeps the
     * days a waiting record holds.
     */
    private val rebuildDerived: suspend (Collection<String>) -> Unit by lazy {
        history.derivePassOf { emptied: Collection<String> ->
            derivedMutex.withLock {
                val byThing = index.observeAll().first().groupBy { it.thing }
                rebuild(byThing.keys + emptied, byThing)
            }
        }
    }

    /** Rebuilds the derived days of the things it is given, as one pass, after a template change touched them. */
    private val rebuildThings: suspend (Collection<String>) -> Unit by lazy {
        history.derivePassOf { things: Collection<String> ->
            derivedMutex.withLock { rebuild(things, index.observeAll().first().groupBy { it.thing }) }
        }
    }

    /** Called under [derivedMutex] inside a pass. */
    private suspend fun rebuild(things: Collection<String>, byThing: Map<String, List<ArchiveRecord>>) {
        for (thing in things) {
            val files = byThing[thing].orEmpty()
            val anchors = TimeEstimate.anchors(files.filter { it.isIndexed }.map { it.info() }, clockAnchors.anchors(thing))
            derived.rebuild(thing, files, store, anchors, Clock.System.now().epochSeconds)
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
        // The startup sweep of #60, the feed block quota, the backfill and the derive, in the shared order.
        historyStartup.start(scope, deriveThings = { rebuildThings(it) }) { rebuildDerived(emptyList()) }
        // Cached served templates register before a sensor's own copy is asked for.
        scope.launch {
            try {
                boardDescriptors.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("board template cache unreadable: ${e::class.simpleName}")
            }
        }
        // Every archive change, a sync's new file or a removal, derives again after any backfill in flight.
        scope.launch {
            index.observeAll().conflate().collect {
                try {
                    rebuildDerived(emptyList())
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
 * The follow-up to a saved clock anchor, run on [scope] after each request. The shared
 * [HistoryOrchestrator.afterClockReceipt] runs the time estimates as one pass, then [derive].
 */
internal fun clockAnchorFollowUp(scope: CoroutineScope, history: HistoryOrchestrator, derive: suspend () -> Unit): CoalescedWork =
    CoalescedWork(scope, "clock anchor estimate") { history.afterClockReceipt(derive) }

/**
 * The sync runner over the process archive, so History sees what it saves. Swift calls this once
 * (`IosArchiveKt.sharedSensorSync()`), which also starts the History index repair.
 */
fun sharedSensorSync(): SensorSync {
    IosArchive.startIndexRepair()
    return SensorSync(IosArchive.store, IosArchive.index, Dispatchers.Default, Dispatchers.Main, recordClockAnchor = IosArchive::recordClockAnchor, developer = { settingsRepository.current().detailLevel.developer }, budget = IosArchive.budget, coordinator = IosArchive.coordinator, cursors = IosArchive.cursors, sources = IosArchive.sources)
}

/** `Library/Caches` of this app. */
internal fun cachesDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSCachesDirectory, NSUserDomainMask, null, true, null)
    return checkNotNull(url?.path) { "Library/Caches is unavailable" }
}

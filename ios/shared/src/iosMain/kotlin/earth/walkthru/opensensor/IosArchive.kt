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
import earth.walkthru.aqsync.data.history.TimeEstimate
import earth.walkthru.aqsync.data.history.info
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.IosStorageTree
import earth.walkthru.aqsync.storage.StorageJanitor
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
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
    val tiers: ArchiveHistoryTiers by lazy { ArchiveHistoryTiers(store, index, clockAnchors = clockAnchors) }
    val reader: HistorySeriesReader by lazy { HistorySeriesReader(store, index, tiers, clockAnchors = clockAnchors) }

    private val derivedMutex = Mutex()
    private val derived by lazy { IosDailyDerivedStore(store.finalRoot.substringBeforeLast('/')) }
    private val repair by lazy { ArchiveIndexRepair(store, index, clockAnchors = clockAnchors) }
    private val janitor by lazy {
        val base = store.finalRoot.substringBeforeLast('/')
        val roots = StorageRoots(
            mapOf(
                StorageRole.Archive to store.finalRoot,
                StorageRole.Partials to store.partialRoot,
                StorageRole.ClockAnchors to clockAnchorsRoot,
                StorageRole.Derived to "$base/${ArchiveLayout.DERIVED}",
                StorageRole.Temp to "$base/${AtomicFiles.TEMP_DIR}",
                StorageRole.Exports to IosExportSpace.temporaryRoot(),
            ),
        )
        StorageJanitor(roots, IosStorageTree(), store, index, Dispatchers.IO, log = ::println)
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
    }

    /**
     * History index repair (F39): at start, and again whenever a record is waiting for its index,
     * fills the index columns of every verified file from its Parquet footer. It only reads files.
     * Call on the main thread. Safe to call more than once, the first call starts it.
     */
    fun startIndexRepair() {
        if (started) return
        started = true
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
    return SensorSync(IosArchive.store, IosArchive.index, Dispatchers.Default, Dispatchers.Main, recordClockAnchor = IosArchive::recordClockAnchor)
}

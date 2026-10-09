package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.data.history.ArchiveHistoryTiers
import earth.walkthru.aqsync.data.history.ArchiveIndexRepair
import earth.walkthru.aqsync.data.history.HistorySeriesReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one phone archive of the iOS process: the store, its receipt index, the in-memory History
 * tiers and the History reader. The sync runner and History share the same index instance, so a file
 * saved by a sync shows in History without a relaunch.
 */
internal object IosArchive {
    val store: IosArchiveStore by lazy { IosArchiveStore.inApplicationSupport() }
    val index: ArchiveIndex by lazy { ReceiptArchiveIndex(store.finalRoot) }
    val tiers: ArchiveHistoryTiers by lazy { ArchiveHistoryTiers(store, index) }
    val reader: HistorySeriesReader by lazy { HistorySeriesReader(store, index, tiers) }

    private val repair by lazy { ArchiveIndexRepair(store, index) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /**
     * History index repair (F39): at start, and again whenever a record is waiting for its index,
     * fills the index columns of every verified file from its Parquet footer. It only reads files.
     * Call on the main thread. Safe to call more than once, the first call starts it.
     */
    fun startIndexRepair() {
        if (started) return
        started = true
        scope.launch { repair.repairWhenWaiting() }
    }
}

/**
 * The sync runner over the process archive, so History sees what it saves. Swift calls this once
 * (`IosArchiveKt.sharedSensorSync()`), which also starts the History index repair.
 */
fun sharedSensorSync(): SensorSync {
    IosArchive.startIndexRepair()
    return SensorSync(IosArchive.store, IosArchive.index, Dispatchers.Default, Dispatchers.Main)
}

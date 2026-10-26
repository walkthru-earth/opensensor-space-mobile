package space.opensensor.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.opensensor.archive.PrunedFile
import space.opensensor.data.local.database.ArchivedFileIdentity
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.sync.ArchivedIdentity
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.SyncPlan
import space.opensensor.data.sync.SyncPlanner

/** How often a progress tick may redo the overlay. The transfer reports every 100 ms. */
internal const val PROGRESS_SAMPLE_MS = 250L

/**
 * Manifest joined with the archive, with the running transfer on top. The base plan, which walks
 * the partial directory, is rebuilt only when the listing, the thing, that thing's archived
 * identities, the [pruned] files or the running flag changes. A progress tick only redoes the cheap overlay, at most
 * once per [sampleMs] (#57 and #63, performance audit B2).
 *
 * [archived] observes one thing's identity columns ([ArchivedFileDao.observeIdentitiesForThing]),
 * so a write for another thing or to the history index columns never replans.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun planWithProgress(
    manifest: Flow<ManifestState>,
    info: Flow<DeviceInfo?>,
    archived: (String) -> Flow<List<ArchivedFileIdentity>>,
    sync: Flow<SyncProgress>,
    partialLengths: (String) -> Map<String, Long>,
    pruned: Flow<List<PrunedFile>> = flowOf(emptyList()),
    planner: (String, List<Frame.File>, List<ArchivedIdentity>, Map<String, Long>, List<PrunedFile>) -> SyncPlan = SyncPlanner::plan,
    sampleMs: Long = PROGRESS_SAMPLE_MS,
): Flow<SyncPlan?> {
    // The thing travels with its rows, so a thing switch never plans new rows against the old thing.
    val identities = info.map { it?.thing }.distinctUntilChanged().flatMapLatest { thing ->
        if (thing == null) flowOf(null) else archived(thing).distinctUntilChanged().map { thing to it }
    }
    val base = combine(
        manifest.map { it.last }.distinctUntilChanged(),
        identities,
        pruned.distinctUntilChanged(),
        sync.map { it.running }.distinctUntilChanged(),
    ) { loaded, thingRows, removed, _ ->
        // The last successful listing, kept through a refresh or its failure (see ManifestState).
        if (thingRows == null || loaded == null) return@combine null
        val (thing, rows) = thingRows
        // Partials are read here only, never on a progress tick. The overlay shows the live byte count.
        planner(thing, loaded.files, rows.map { it.toIdentity() }, partialLengths(thing), removed)
    }
    return combine(base, sync.sampleTicks(sampleMs)) { plan, progress -> plan?.let { overlayProgress(it, progress) } }
}

internal fun ArchivedFileIdentity.toIdentity() = ArchivedIdentity(thing, name, bytes, crc32)

/**
 * Progress at most once per [periodMs], keeping the newest tick of each window. A change of the
 * running flag, the outcome count or the current file passes at once and drops the pending tick, so
 * the end of a run is never lost or delayed. One collector sends both kinds, so a stale tick can never
 * follow a newer state, which `merge(sample(..), ..)` could not promise. No timer runs while idle.
 */
internal fun Flow<SyncProgress>.sampleTicks(periodMs: Long): Flow<SyncProgress> = channelFlow {
    val lock = Mutex()
    var sent: SyncProgress? = null
    var pending: SyncProgress? = null
    var timer: Job? = null
    collect { p ->
        lock.withLock {
            val prev = sent
            if (prev == null || prev.running != p.running || prev.outcomes.size != p.outcomes.size || prev.current?.name != p.current?.name) {
                timer?.cancel()
                timer = null
                pending = null
                sent = p
                send(p)
            } else {
                pending = p
                if (timer == null) {
                    timer = launch {
                        delay(periodMs)
                        lock.withLock {
                            timer = null
                            pending?.let {
                                pending = null
                                sent = it
                                send(it)
                            }
                        }
                    }
                }
            }
        }
    }
    // A finite upstream (tests) ends with its newest tick.
    lock.withLock {
        timer?.cancel()
        pending?.let { send(it) }
    }
}

private fun overlayProgress(plan: SyncPlan, sync: SyncProgress): SyncPlan {
    if (!sync.running && sync.outcomes.isEmpty()) return plan
    val failures = sync.failures.associateBy { it.name }
    val entries = plan.entries.map { e ->
        val cur = sync.current
        when {
            sync.running && cur != null && cur.name == e.name && e.state != FileSyncState.SYNCED ->
                e.copy(state = FileSyncState.SYNCING, partialBytes = cur.bytesDone)
            e.state != FileSyncState.SYNCED && failures[e.name] != null -> {
                val f = failures.getValue(e.name)
                val msg = when (f) {
                    is FileOutcome.Mismatch -> f.reason
                    is FileOutcome.DeviceError -> f.message
                    is FileOutcome.Failed -> f.message
                    is FileOutcome.Synced, is FileOutcome.Removed -> null
                }
                e.copy(state = if (f is FileOutcome.Mismatch) FileSyncState.MISMATCH else e.state, detail = msg)
            }
            else -> e
        }
    }
    return plan.copy(entries = entries)
}

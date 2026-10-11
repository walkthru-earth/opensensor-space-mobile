package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.CollectionProgress
import earth.walkthru.aqsync.data.sync.CollectionSummary
import earth.walkthru.aqsync.data.sync.FileProblem
import earth.walkthru.aqsync.data.sync.InsufficientSpaceException
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.data.sync.WriterLeases
import earth.walkthru.aqsync.storage.IosStorageBudget
import earth.walkthru.aqsync.storage.StorageBudget
import earth.walkthru.aqsync.storage.StorageRole
import earth.walkthru.aqsync.storage.StorageRoots
import earth.walkthru.opensensor.designsystem.nowEpochMillis
import earth.walkthru.opensensor.today.TodayNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSLock
import platform.Foundation.NSLog
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Stops one sync. Safe from any thread and more than once. */
class SyncHandle internal constructor(private val job: Job?) {
    fun cancel() {
        job?.cancel()
    }
}

/**
 * The Swift-facing runner for the shared engine. It owns the archive store, the receipt index and
 * the [ArchiveCollector], so LAN and Bluetooth save into the same archive under the same rules.
 *
 * Make one per app and call [start] on the main thread. Work runs off the main thread on a lease of
 * the [IosLink]. Every callback arrives on the main thread, `progress` at most four times a second
 * and in order, `finished` exactly once, last. A station syncs over one link at a time, so a second
 * physical link to the same station (Bluetooth and Wi-Fi) waits its turn. The runner maintains UTC
 * if needed, then lists, opens, reads and closes files. It never asks the sensor to delete, rename
 * or rewrite one.
 */
class SensorSync internal constructor(
    store: ArchiveStore,
    /** The receipt index, read by My devices for its archive line. */
    internal val index: ArchiveIndex,
    private val work: CoroutineContext,
    private val callbacks: CoroutineContext,
    private val progressInterval: Long = PROGRESS_INTERVAL_MS,
    private val recordClockAnchor: suspend (String, String, Long, Long) -> Unit = { _, _, _, _ -> },
    budget: StorageBudget,
    /** Shared with the storage janitor, so it never sweeps a partial this runner is writing. */
    leases: WriterLeases = WriterLeases(),
) {
    constructor() : this(IosArchiveStore.inApplicationSupport(), Dispatchers.Default, Dispatchers.Main)

    private constructor(store: IosArchiveStore, work: CoroutineContext, callbacks: CoroutineContext) :
        this(store, ReceiptArchiveIndex(store.finalRoot), work, callbacks, budget = IosStorageBudget(StorageRoots(mapOf(StorageRole.Archive to store.finalRoot))))

    private val collector = ArchiveCollector(store, index, SyncEngine(store, index, leases = leases, log = ::syncLog), budget)
    private val scope = CoroutineScope(SupervisorJob())
    private val lock = NSLock()
    private val active = HashSet<String>()

    /**
     * Collects every file of [station] that this phone lacks over [link]. [device] is the hardware
     * id recorded with each file. [maxRead] is the sensor's advertised read window.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun start(
        link: IosLink,
        station: String,
        device: String,
        maxRead: Int,
        progress: (SyncProgress) -> Unit,
        finished: (SyncResult) -> Unit,
    ): SyncHandle = startSelected(link, station, device, maxRead, null, progress, finished)

    /** One exact listed file for Files retry, with the same verification and station writer lock. */
    @OptIn(DelicateCoroutinesApi::class)
    fun startSelected(
        link: IosLink,
        station: String,
        device: String,
        maxRead: Int,
        onlyName: String?,
        progress: (SyncProgress) -> Unit,
        finished: (SyncResult) -> Unit,
    ): SyncHandle {
        IosSyncDiagnostics.record(device, "sync", "${link.kind.name.lowercase()} ${if (onlyName == null) "all files" else "one file"}")
        if (!claim(station)) {
            scope.launch(callbacks) { finished(SyncResult(SyncEnd.IN_USE, 0, 0, 0)) }
            return SyncHandle(null)
        }
        // ATOMIC runs the body even when cancel() lands before dispatch, so the station is released
        // and `finished` fires on every path.
        val job = scope.launch(work, CoroutineStart.ATOMIC) {
            var listed = 0
            var initial = -1
            var done = 0
            // Throttled ticks, newest first, handed to the main thread one at a time and before `finished`.
            val ticks = Channel<SyncProgress>(Channel.CONFLATED)
            val reporter = launch { for (update in ticks) withContext(callbacks) { progress(update) } }
            val result = try {
                var last: TimeSource.Monotonic.ValueTimeMark? = null
                var lastDone = -1
                link.leased(LeaseKind.SYNC) { session ->
                    try {
                        synchronizeClock(session, link, automatic = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Preserve immutable unsynced files even when UTC could not be refreshed.
                        // A broken pipe will still fail the normal LIST/transfer checks.
                        syncLog("automatic UTC unavailable, ${e::class.simpleName}; collecting original files")
                    }
                    val summary = collector.collect(session, station, device, maxRead, onlyName) { p: CollectionProgress ->
                        listed = p.filesTotal
                        if (initial < 0) initial = p.filesDone
                        done = p.filesDone
                        val now = TimeSource.Monotonic.markNow()
                        val due = last?.let { (now - it) >= progressInterval.milliseconds } ?: true
                        if (due || p.filesDone != lastDone) {
                            last = now
                            lastDone = p.filesDone
                            ticks.trySend(SyncProgress(p.filesDone, p.filesTotal, p.fileBytes, p.receivedBytes, p.bytesPerSecond, p.bytesTotal))
                        }
                    }
                    resultOf(summary, (done - initial.coerceAtLeast(0)))
                }
            } catch (e: CancellationException) {
                SyncResult(SyncEnd.STOPPED, listed, 0, 0)
            } catch (e: LeaseRefusedException) {
                SyncResult(if (e.syncRunning) SyncEnd.IN_USE else SyncEnd.LINK_LOST, listed, 0, 0)
            } catch (e: AuthFailedException) {
                SyncResult(SyncEnd.AUTH_FAILED, listed, 0, 0)
            } catch (e: DeviceBusyException) {
                SyncResult(SyncEnd.BUSY, listed, 0, 0)
            } catch (e: InsufficientSpaceException) {
                // Refused before any OPEN, or a full disk mid-run. Partials stay for resume.
                syncLog("sync refused, ${e.available} bytes free of ${e.needed}")
                SyncResult(SyncEnd.STORAGE_FAILED, listed, 0, 0)
            } catch (e: TransportException) {
                SyncResult(SyncEnd.LINK_LOST, listed, 0, 0)
            } catch (e: Exception) {
                // A protocol fault, or the archive failing outside one file. The class and message only.
                syncLog("sync ended, ${e::class.simpleName}: ${e.message}")
                SyncResult(SyncEnd.FAILED, listed, 0, 0)
            }
            ticks.close()
            withContext(NonCancellable) { if (result.end == SyncEnd.STOPPED) reporter.cancel() else reporter.join() }
            withContext(NonCancellable + callbacks) {
                release(station)
                IosSyncDiagnostics.record(device, "result", "${result.end.name.lowercase()} · ${result.saved}/${result.files} saved")
                finished(result)
            }
        }
        return SyncHandle(job)
    }

    /**
     * The one clock owner on iOS. The automatic check runs once per connection, whichever of the
     * connect step and the first sync gets the link first. Called under a lease.
     */
    private suspend fun synchronizeClock(session: DeviceSession, link: IosLink, automatic: Boolean = false) {
        if (automatic && link.clockChecked) return
        val station = link.station
        val boot = link.boot
        val anchor = if (automatic) session.synchronizeClockIfNeeded(::nowEpochMillis) else session.synchronizeClock(::nowEpochMillis)
        link.clockChecked = true
        if (anchor == null) return
        if (station != null && boot != null) {
            recordClockAnchor(station, boot, anchor.epochNanos, anchor.monotonicMicros)
        }
    }

    /**
     * Sends automatic UTC or a confirmed flush over [link] through [DeviceSession]. `SET_TIME` carries
     * this phone's clock and then asks for a fresh `status` push, so Today shows the anchored clock.
     * `FLUSH` finalizes the buffered rows. Neither touches the archive files. `finished` fires
     * exactly once on the main thread, also when the link closes or [SyncHandle.cancel] lands, which
     * ends it stopped with no notice.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun command(link: IosLink, command: SensorCommand, automatic: Boolean = false, finished: (CommandResult) -> Unit): SyncHandle {
        val job = scope.launch(work, CoroutineStart.ATOMIC) {
            val notice = try {
                link.leased(LeaseKind.COMMAND) { session ->
                    when (command) {
                        SensorCommand.SET_TIME -> {
                            synchronizeClock(session, link, automatic)
                            try {
                                session.requestStatus()
                            } catch (e: TransportException) {
                                // The clock is set. The next status push shows it.
                            }
                            TodayNotice.ClockSet
                        }
                        SensorCommand.FLUSH -> {
                            val flushed = session.flush()
                            TodayNotice.Finalized(flushed.rows, flushed.filesFinalized.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        }
                    }
                }
            } catch (e: CancellationException) {
                null
            } catch (e: LeaseRefusedException) {
                TodayNotice.Busy
            } catch (e: DeviceBusyException) {
                TodayNotice.Busy
            } catch (e: DeviceErrorException) {
                TodayNotice.Rejected(command.today)
            } catch (e: Exception) {
                syncLog("${command.name} ended, ${e::class.simpleName}")
                TodayNotice.Failed(command.today)
            }
            withContext(NonCancellable + callbacks) { finished(CommandResult(notice)) }
        }
        return SyncHandle(job)
    }

    private fun resultOf(summary: CollectionSummary, attempted: Int): SyncResult {
        val failed = summary.notSaved.size
        val end = when {
            summary.complete -> SyncEnd.COMPLETE
            summary.notSaved.any { it.problem == FileProblem.STORAGE } -> SyncEnd.STORAGE_FAILED
            else -> SyncEnd.FILES_NOT_SAVED
        }
        // Files planned away (unsafe names, local conflicts) are counted as not saved and not attempted.
        val saved = (attempted - summary.notSaved.count { it.problem.attempted }).coerceAtLeast(0)
        return SyncResult(end, summary.files, saved, failed, summary.notSaved)
    }

    private fun claim(station: String): Boolean {
        lock.lock()
        try {
            return active.add(station)
        } finally {
            lock.unlock()
        }
    }

    private fun release(station: String) {
        lock.lock()
        try {
            active.remove(station)
        } finally {
            lock.unlock()
        }
    }

    internal companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

/** One line to the device log. Callers pass file names and error classes, never bytes or secrets. */
private fun syncLog(line: String) {
    // No format arguments, a variadic call from Kotlin is not safe here. Escape the format marker instead.
    NSLog("SensorSync $line".replace("%", "%%"))
}

/** Unsafe names and local conflicts are decided by the plan and never downloaded. */
private val FileProblem.attempted: Boolean
    get() = this != FileProblem.INVALID_ENTRY && this != FileProblem.LOCAL_CONFLICT

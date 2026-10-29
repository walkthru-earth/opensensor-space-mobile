package space.opensensor

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
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveStore
import space.opensensor.collection.CollectionFailure
import space.opensensor.collection.LinkPreference
import space.opensensor.collection.SyncEndReason
import space.opensensor.collection.endReason
import space.opensensor.data.ble.AuthFailedException
import space.opensensor.data.ble.DeviceBusyException
import space.opensensor.data.ble.DeviceErrorException
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.sync.ArchiveCollector
import space.opensensor.data.sync.CollectionProgress
import space.opensensor.data.sync.CollectionSummary
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.FileProblem
import space.opensensor.data.sync.InsufficientSpaceException
import space.opensensor.data.sync.ListCursors
import space.opensensor.data.sync.SyncEngine
import space.opensensor.data.sync.ThingSources
import space.opensensor.data.sync.failed
import space.opensensor.designsystem.nowEpochMillis
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkTimeoutException
import space.opensensor.model.ThingId
import space.opensensor.storage.StorageBudget
import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.sourceFor
import space.opensensor.today.TodayNotice
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Stops one sync. Safe from any thread and more than once. */
class SyncHandle internal constructor(private val stop: () -> Unit) {
    internal constructor(job: Job?) : this({ job?.cancel() })

    fun cancel() {
        stop()
    }
}

/**
 * The Swift-facing runner for the shared engine. It holds the archive store, the archive index and
 * the [ArchiveCollector], so LAN and Bluetooth save into the same archive under the same rules.
 *
 * Make one per app. Swift syncs through [collections] and sends commands through [command]. The
 * direct single-link [start] is internal, for host tests of the runner. Work runs off the main
 * thread on a lease of the [IosLink]. Every callback arrives on the main thread, `progress` at
 * most four times a second and in order, `finished` exactly once, last. A thing syncs over one link at a time, so a second
 * physical link to the same thing (Bluetooth and Wi-Fi) waits its turn. The runner maintains UTC
 * if needed, then lists, opens, reads and closes files. It never asks the sensor to delete, rename
 * or rewrite one.
 */
class SensorSync internal constructor(
    store: ArchiveStore,
    /** The archive index, read by My devices for its archive line. */
    internal val index: ArchiveIndex,
    private val work: CoroutineContext,
    private val callbacks: CoroutineContext,
    private val progressInterval: Long = PROGRESS_INTERVAL_MS,
    private val recordClockAnchor: suspend (String, String, Long, Long) -> Unit = { _, _, _, _ -> },
    /** Whether the phone is at the Developer level, read once per [upkeep]. Only it may keep Wi-Fi sync off. */
    private val developer: suspend () -> Boolean = { false },
    budget: StorageBudget,
    /**
     * The archive's one coordinator, shared with the index repair, the storage janitor and the old
     * file cleanup. It admits one sync per thing and holds the writer leases, thing gates and
     * the tombstones of files removed in Settings.
     */
    coordinator: DeviceCoordinator = DeviceCoordinator(),
    /** Where each station's incremental list left off. */
    cursors: ListCursors = ListCursors.None,
    /**
     * Where each Thing's data comes from. Every listing records the board's source once. It has no
     * default, so the app cannot build a runner that forgets the database's sources.
     */
    sources: ThingSources,
) {
    internal val collector = ArchiveCollector(
        store,
        index,
        SyncEngine(store, index, coordinator = coordinator, log = ::syncLog),
        budget,
        cursors = cursors,
        recordSource = { thing -> sources.recordIfAbsent(OpenSensorBoardKind.sourceFor(thing)) },
    )
    private val scope = CoroutineScope(SupervisorJob())
    private val lock = NSLock()

    /** The one [collections] instance. Guarded by [lock]. */
    private var shared: SensorCollections? = null

    /**
     * Collects every file of [thing] that this phone lacks over [link]. [device] is the hardware
     * id recorded with each file. [maxRead] is the sensor's advertised read window.
     */
    internal fun start(
        link: IosLink,
        thing: String,
        device: String,
        maxRead: Int,
        progress: (SyncProgress) -> Unit,
        finished: (SyncResult) -> Unit,
    ): SyncHandle = startSelected(link, thing, device, maxRead, null, progress, finished)

    /** One exact listed file, with the same verification and thing writer lock. */
    @OptIn(DelicateCoroutinesApi::class)
    internal fun startSelected(
        link: IosLink,
        thing: String,
        device: String,
        maxRead: Int,
        onlyName: String?,
        progress: (SyncProgress) -> Unit,
        finished: (SyncResult) -> Unit,
    ): SyncHandle {
        IosSyncDiagnostics.record(device, "sync", "${link.kind.name.lowercase()} ${if (onlyName == null) "all files" else "one file"}")
        if (!collector.coordinator.tryAdmit(thing)) {
            scope.launch(callbacks) { finished(SyncResult(SyncEnd.IN_USE, 0, 0, 0)) }
            return SyncHandle(null)
        }
        // ATOMIC runs the body even when cancel() lands before dispatch, so the thing is released
        // and `finished` fires on every path.
        val job = scope.launch(work, CoroutineStart.ATOMIC) {
            var listed = 0
            var initial = -1
            var done = 0
            // Settled files by name, so a run that ends early still reports what it saved.
            val outcomes = linkedMapOf<String, FileOutcome>()
            // Throttled ticks, newest first, handed to the main thread one at a time and before `finished`.
            val ticks = Channel<SyncProgress>(Channel.CONFLATED)
            val reporter = launch { for (update in ticks) withContext(callbacks) { progress(update) } }
            val result = try {
                var last: TimeSource.Monotonic.ValueTimeMark? = null
                var lastDone = -1
                link.leased(LeaseKind.SYNC) { session ->
                    try {
                        synchronizeClock(session, link)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Preserve immutable unsynced files even when UTC could not be refreshed.
                        // A broken pipe will still fail the normal LIST/transfer checks.
                        syncLog("automatic UTC unavailable, ${e::class.simpleName}; collecting original files")
                    }
                    val listing = collector.list(session, thing, onlyName)
                    val planned = collector.plan(thing, listing.files, onlyName, listing.whole)
                    val summary = collector.transfer(session, planned, thing, device, maxRead, onOutcome = { outcomes.record(it) }) { p: CollectionProgress ->
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
                    collector.settle(thing, listing, summary)
                    resultOf(summary, (done - initial.coerceAtLeast(0)))
                }
            } catch (e: CancellationException) {
                partialResult(SyncEnd.STOPPED, listed, outcomes)
            } catch (e: LeaseRefusedException) {
                partialResult(if (e.syncRunning) SyncEnd.IN_USE else SyncEnd.LINK_LOST, listed, outcomes)
            } catch (e: AuthFailedException) {
                partialResult(SyncEnd.AUTH_FAILED, listed, outcomes)
            } catch (e: DeviceBusyException) {
                partialResult(SyncEnd.BUSY, listed, outcomes)
            } catch (e: InsufficientSpaceException) {
                // Refused before any OPEN, or a full disk mid-run. Partials stay for resume.
                syncLog("sync refused, ${e.available} bytes free of ${e.needed}")
                partialResult(SyncEnd.STORAGE_FAILED, listed, outcomes, SyncEndReason.NO_SPACE)
            } catch (e: TransportException) {
                // Keep the typed cause, so a radio that is off or a rotated token is not worded as a lost link.
                val failure = CollectionFailure.Link(e.disconnectCause())
                partialResult(failure.syncEnd(), listed, outcomes, failure.endReason())
            } catch (e: Exception) {
                // A protocol fault, or the archive failing outside one file. The class only, a message could carry config text.
                syncLog("sync ended, ${e::class.simpleName}")
                partialResult(SyncEnd.FAILED, listed, outcomes)
            }
            ticks.close()
            withContext(NonCancellable) { if (result.end == SyncEnd.STOPPED) reporter.cancel() else reporter.join() }
            withContext(NonCancellable + callbacks) {
                collector.coordinator.release(thing)
                IosSyncDiagnostics.record(device, "result", "${result.end.name.lowercase()} · ${result.saved}/${result.files} saved")
                finished(result)
            }
        }
        return SyncHandle(job)
    }

    /**
     * The one clock owner on iOS. The automatic check runs once per connection, whichever of
     * [upkeep] and the first sync gets the link first. Called under a lease.
     */
    private suspend fun synchronizeClock(session: DeviceSession, link: IosLink) {
        val anchor = session.synchronizeClockOnce(::nowEpochMillis) ?: return
        val thing = link.thing
        val boot = link.boot
        if (thing != null && boot != null) {
            recordClockAnchor(thing, boot, anchor.epochNanos, anchor.monotonicMicros)
        }
    }

    /**
     * The quiet per-connection upkeep, the same steps as Android's connect. Swift calls it once when
     * a Bluetooth or Wi-Fi link is first usable. Under one lease it checks the sensor clock (and
     * asks for a fresh status push when it set it), then reads the config and switches Wi-Fi sync
     * back on below the Developer level. Each step runs at most once per connection, also when a
     * run got there first. Failures reach the device log by class only and nothing is shown.
     * `finished` fires exactly once on the main thread, also when the link closes or
     * [SyncHandle.cancel] lands.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun upkeep(link: IosLink, finished: () -> Unit): SyncHandle {
        val job = scope.launch(work, CoroutineStart.ATOMIC) {
            try {
                link.leased(LeaseKind.COMMAND) { session -> upkeepUnderLease(session, link) }
            } catch (e: CancellationException) {
                // Stopped or the link closed, the next connection runs it again.
            } catch (e: Exception) {
                syncLog("upkeep skipped, ${e::class.simpleName}")
            }
            withContext(NonCancellable + callbacks) { finished() }
        }
        return SyncHandle(job)
    }

    private suspend fun upkeepUnderLease(session: DeviceSession, link: IosLink) {
        try {
            val set = session.synchronizeClockOnce(::nowEpochMillis)
            if (set != null) {
                val thing = link.thing
                val boot = link.boot
                if (thing != null && boot != null) recordClockAnchor(thing, boot, set.epochNanos, set.monotonicMicros)
                // Today shows the anchored clock from the next push.
                session.requestStatus()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLog("clock check failed, ${e::class.simpleName}")
        }
        // Every identified iOS link speaks protocol 3, so it always has a config to read.
        try {
            val config = session.getConfig().config
            if (session.enableLanOnce(config, developer()) != null) syncLog("Wi-Fi sync switched back on")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLog("Wi-Fi sync check failed, ${e::class.simpleName}")
        }
    }

    /**
     * Sends a confirmed flush over [link] through [DeviceSession]. `FLUSH` finalizes the buffered
     * rows and never touches the archive files. `finished` fires exactly once on the main thread,
     * also when the link closes or [SyncHandle.cancel] lands, which ends it stopped with no notice.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun command(link: IosLink, command: SensorCommand, finished: (CommandResult) -> Unit): SyncHandle {
        val job = scope.launch(work, CoroutineStart.ATOMIC) {
            val notice = try {
                link.leased(LeaseKind.COMMAND) { session ->
                    when (command) {
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

    /**
     * The shared collection controllers over this runner's archive (#81). [linkFor] returns the
     * open link Swift holds for a device, on the main thread. [dial] asks Swift to open one when a
     * retry finds none, and Swift calls its `done` once with whether the link opened and identified
     * (see [IosLinkProvider]). A thing admitted here or there syncs over one link at a time.
     *
     * The first call creates the controllers and every later call returns the same instance, so the
     * per-device busy guard and the run permits hold app wide. Later closures are ignored.
     */
    fun collections(linkFor: (String) -> IosLink?, dial: (String, LinkPreference, (DialResult) -> Unit) -> Unit): SensorCollections {
        lock.lock()
        try {
            return shared ?: SensorCollections(this, IosLinkProvider(linkFor, dial), recordClockAnchor, work, callbacks, progressInterval = progressInterval)
                .also { shared = it }
        } finally {
            lock.unlock()
        }
    }

    /**
     * The app's collections (#81). [pick] returns Swift's open link for a device and transport, on
     * the main thread. [dial] opens one as above. [protect] runs on the main thread when a run starts,
     * with the device, the run id and whether it is unattended, and returns false when iOS gives the
     * run no time to finish, which refuses it. [unprotect] runs on the main thread once that run
     * handed its link back, before its `finished`. The first call of either overload wins.
     */
    fun collections(
        pick: (String, LinkPreference) -> IosLink?,
        dial: (String, LinkPreference, (DialResult) -> Unit) -> Unit,
        protect: (ThingId.BleAddress, Long, Boolean) -> Boolean,
        unprotect: (Long) -> Unit,
    ): SensorCollections {
        lock.lock()
        try {
            return shared ?: SensorCollections(
                this,
                IosLinkProvider({ null }, 20.seconds, Dispatchers.Main, dial, pick = pick),
                recordClockAnchor,
                work,
                callbacks,
                keepAlive = IosKeepAlive(protect, unprotect, callbacks),
                progressInterval = progressInterval,
            ).also { shared = it }
        } finally {
            lock.unlock()
        }
    }

    internal companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

/** One line to the device log. Callers pass file names and error classes, never bytes or secrets. */
internal fun syncLog(line: String) {
    // No format arguments, a variadic call from Kotlin is not safe here. Escape the format marker instead.
    NSLog("SensorSync $line".replace("%", "%%"))
}

/**
 * Keeps the latest outcome of each file. A file this run copied stays copied when a later attempt
 * finds it already archived, as in the shared controller.
 */
internal fun MutableMap<String, FileOutcome>.record(outcome: FileOutcome) {
    val previous = this[outcome.name]
    val keepNew = previous is FileOutcome.Synced && !previous.alreadyArchived && outcome is FileOutcome.Synced && outcome.alreadyArchived
    if (!keepNew) this[outcome.name] = outcome
}

/** A run that ended before its summary. [listed] files were listed and the outcomes are real. */
internal fun partialResult(end: SyncEnd, listed: Int, outcomes: Map<String, FileOutcome>, reason: SyncEndReason = end.reason): SyncResult {
    val saved = outcomes.values.count { it is FileOutcome.Synced && !it.alreadyArchived }
    val failed = outcomes.values.count { it.failed }
    return SyncResult(end, listed, saved, failed, endReason = reason)
}

/** The typed cause a transport failure carries, as the shared controller classifies it. */
internal fun TransportException.disconnectCause(): DisconnectCause = when (this) {
    is LinkClosedException -> disconnectCause
    is LinkTimeoutException -> timeout
    is TransportUnavailableException -> DisconnectCause.Unavailable(kind)
    else -> DisconnectCause.LocalAbort(gattStatus = gattStatus)
}

/** The coarse ending the native adapters branch on. The shared [endReason] carries the detail. */
internal fun CollectionFailure.syncEnd(): SyncEnd = when (this) {
    is CollectionFailure.Link -> when (cause) {
        is DisconnectCause.Auth -> SyncEnd.AUTH_FAILED
        DisconnectCause.Busy -> SyncEnd.BUSY
        is DisconnectCause.Deliberate -> SyncEnd.STOPPED
        else -> SyncEnd.LINK_LOST
    }
    is CollectionFailure.Storage -> SyncEnd.STORAGE_FAILED
    // The OS ended the time off screen. Nothing is wrong with the link or the files, and the run resumes.
    is CollectionFailure.KeepAliveRefused -> SyncEnd.STOPPED
    is CollectionFailure.Device, is CollectionFailure.Unexpected -> SyncEnd.FAILED
}

/** Unsafe names and local conflicts are decided by the plan and never downloaded. */
private val FileProblem.attempted: Boolean
    get() = this != FileProblem.INVALID_ENTRY && this != FileProblem.LOCAL_CONFLICT

package earth.walkthru.opensensor.files

import earth.walkthru.aqsync.archive.ArchiveExport
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ExportEntry
import earth.walkthru.aqsync.archive.ExportRun
import earth.walkthru.aqsync.archive.ExportRuns
import earth.walkthru.aqsync.archive.ExportSink
import earth.walkthru.aqsync.archive.ExportSpace
import earth.walkthru.aqsync.archive.StagedFile
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.storage.StorageException
import earth.walkthru.aqsync.storage.isOutOfSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** What the share sheet or the save picker is asked to show. Android titles its chooser by it. */
enum class ShareKind { FILE, FILES, ARCHIVE }

/**
 * The platform share sheet and save picker. Each call returns once the sheet closed, true when the
 * staged copies may be removed now. Android returns false from [share], its chooser gives no close
 * signal and a target may still be reading, so the run is released to the age sweep instead.
 */
interface ExportPresenter {
    suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean

    suspend fun save(file: StagedFile): Boolean

    /**
     * Saves a zip named [name] by handing [write] the picked document as a sink, so nothing is staged
     * first. Returns false when the picker was dismissed, true once written. Null, the default, means
     * the platform saves a staged copy through [save] instead (iOS exports an existing file URL).
     */
    suspend fun saveStreaming(name: String, write: suspend (ExportSink) -> Unit): Boolean? = null
}

/** One-off notices for the snackbar. */
enum class FilesMessage { NO_DAILY, EXPORT_FAILED, NO_SPACE }

data class FilesState(
    /** The verified files of [owner] when known, else of every station. */
    val records: List<ArchiveRecord> = emptyList(),
    /** The connected sensor's station, from its INFO or enrolment. Null when there is none. */
    val owner: String? = null,
    val exporting: Boolean = false,
    /** Daily files can be exported on this platform (Android derives them, iOS not yet). */
    val dailyAvailable: Boolean = false,
) {
    val archivedBytes: Long get() = shown.sumOf { it.bytes }

    /** The records the list shows without a device listing, the owner's when known, else all. */
    val shown: List<ArchiveRecord> get() = owner?.let { o -> records.filter { it.station == o } } ?: records
}

/**
 * The archive side of the Files screen, the same on Android and iOS. Lists the verified files and
 * stages every share through `:core:archive` ([ArchiveExport]) into a fresh run of [space]. A zip save
 * streams into the picked document when the platform allows ([ExportPresenter.saveStreaming]), else it
 * is staged too. Exports only read the archive. A staged run is removed once the sheet closed (B7), or
 * released to the age sweep of [ExportRuns] when the sheet gives no close signal (#161).
 */
class FilesController(
    private val scope: CoroutineScope,
    private val store: ArchiveStore,
    index: ArchiveIndex,
    private val space: ExportSpace,
    owner: Flow<String?>,
    /** Lists the derived daily files, or null on a platform that has none. */
    private val daily: (suspend () -> List<ExportEntry>)? = null,
    /** Runs the daily listing and zip with the cache quota paused, so no listed day file is evicted. */
    private val holdDaily: suspend (suspend () -> Unit) -> Unit = { it() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val exporting = MutableStateFlow(false)

    /** The owner's records only when there is an owner, so another station's write does not reach the list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val listed: Flow<Pair<String?, List<ArchiveRecord>>> = owner.distinctUntilChanged().flatMapLatest { o ->
        (if (o == null) index.observeAll() else index.observeStation(o)).map { records -> o to records }
    }

    val state: StateFlow<FilesState> = combine(listed, exporting) { (o, records), busy ->
        FilesState(records, o, busy, daily != null)
    }.stateIn(scope, SharingStarted.Eagerly, FilesState(dailyAvailable = daily != null))

    private val _messages = MutableSharedFlow<FilesMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<FilesMessage> = _messages.asSharedFlow()

    /**
     * The verified copy of a listed [name]. A relative path can occur on several sensors or station
     * generations, so without a known owner this fails closed.
     */
    fun archived(name: String): ArchiveRecord? {
        val s = state.value
        val o = s.owner ?: return null
        return s.records.firstOrNull { it.station == o && it.name == name }
    }

    fun shareFile(record: ArchiveRecord, presenter: ExportPresenter) = run(presenter) {
        present(it, stage { ArchiveExport.stageCopies(space, ArchiveExport.recordEntries(store, listOf(record))) }, ShareKind.FILE)
    }

    fun shareAll(presenter: ExportPresenter) = run(presenter) {
        val records = state.value.shown
        if (records.isEmpty()) return@run null
        present(it, stage { ArchiveExport.stageCopies(space, ArchiveExport.recordEntries(store, records)) }, ShareKind.FILES)
    }

    fun shareZip(presenter: ExportPresenter) = run(presenter) {
        val zip = archiveZip() ?: return@run null
        present(it, listOf(zip), ShareKind.ARCHIVE)
    }

    /**
     * Streams the zip straight into the picked document where the platform can, so a save needs no
     * cache copy as large as the archive. Otherwise stages it and saves the copy.
     */
    fun saveZip(presenter: ExportPresenter) = run(presenter) { p ->
        val entries = ArchiveExport.archiveEntries(store, state.value.shown)
        if (entries.isEmpty()) return@run null
        val name = ArchiveExport.archiveZipName(now())
        val streamed = p.saveStreaming(name) { sink -> withContext(io) { ArchiveExport.writeZip(sink, entries, cancelCheck()) } }
        if (streamed != null) return@run streamed
        val check = cancelCheck()
        val zip = stage { listOf(ArchiveExport.stageZip(space, entries, name, check)) }.single()
        removingRun(zip.run) { p.save(zip) }
    }

    fun shareDaily(presenter: ExportPresenter) = run(presenter) {
        val list = daily ?: return@run null
        // The stations the archive zip covers, the owner's alone when there is one (#156).
        val stations = state.value.shown.mapTo(HashSet()) { it.station }
        var zip: StagedFile? = null
        try {
            holdDaily {
                val entries = withContext(io) { ArchiveExport.dailyEntries(list(), stations) }
                if (entries != null) {
                    val check = cancelCheck()
                    zip = stage { listOf(ArchiveExport.stageZip(space, entries, ArchiveExport.dailyZipName(now()), check)) }.single()
                }
            }
        } catch (e: Throwable) {
            discard(zip?.run)
            throw e
        }
        val staged = zip
        if (staged == null) {
            _messages.tryEmit(FilesMessage.NO_DAILY)
            return@run null
        }
        present(it, listOf(staged), ShareKind.ARCHIVE)
    }

    /**
     * Stages on [io]. A run staged just as this export is cancelled is removed, since nothing will
     * show it and nothing else would end its lease.
     */
    private suspend fun stage(block: () -> List<StagedFile>): List<StagedFile> {
        var staged: List<StagedFile> = emptyList()
        try {
            return withContext(io) { block().also { staged = it } }
        } catch (e: Throwable) {
            discard(staged.firstOrNull()?.run)
            throw e
        }
    }

    private suspend fun discard(run: ExportRun?) {
        if (run != null) withContext(NonCancellable + io) { run.delete() }
    }

    /** A zip write checkpoint that stops the blocking copy once this export is cancelled. */
    private suspend fun cancelCheck(): () -> Unit {
        val context = currentCoroutineContext()
        return { context.ensureActive() }
    }

    private suspend fun archiveZip(): StagedFile? {
        val check = cancelCheck()
        return stage {
            val entries = ArchiveExport.archiveEntries(store, state.value.shown)
            if (entries.isEmpty()) emptyList() else listOf(ArchiveExport.stageZip(space, entries, ArchiveExport.archiveZipName(now()), check))
        }.firstOrNull()
    }

    /**
     * Runs one export. [block] stages and presents, and returns whether the run may go now, or null
     * when nothing was staged. Staging happens on [io]. Only one export runs at a time.
     */
    private fun run(presenter: ExportPresenter, block: suspend (ExportPresenter) -> Boolean?) {
        if (exporting.value) return
        exporting.value = true
        scope.launch {
            try {
                block(presenter)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageException) {
                _messages.tryEmit(FilesMessage.NO_SPACE)
            } catch (e: Exception) {
                // A full disk while the platform flushes or closes the document is a plain I/O error.
                _messages.tryEmit(if (isOutOfSpace(e)) FilesMessage.NO_SPACE else FilesMessage.EXPORT_FAILED)
            } finally {
                exporting.value = false
            }
        }
    }

    /** Shows [files] and removes their run when the sheet says it is safe, else releases it. */
    private suspend fun present(presenter: ExportPresenter, files: List<StagedFile>, kind: ShareKind): Boolean =
        removingRun(files.firstOrNull()?.run) { presenter.share(files, kind) }

    /**
     * Runs [show] while [run] stays leased and removes the run once it returns true, the close signal.
     * A sheet that fails, or a wait for it that is cancelled, removes the run too, since nothing still
     * reads it and it can be as large as the archive. An explicit false (Android's chooser, whose
     * targets read by URI later) releases the run, which the age sweep of [ExportRuns] removes after
     * [ExportRuns.MAX_AGE_MS]. A later export never removes it.
     */
    private suspend fun removingRun(run: ExportRun?, show: suspend () -> Boolean): Boolean {
        val done = try {
            show()
        } catch (e: Throwable) {
            discard(run)
            throw e
        }
        withContext(NonCancellable + io) { if (done) run?.delete() else run?.release() }
        return done
    }

    companion object {
        /** The archive alone as list entries, every one synced, for the screen without a device listing. */
        fun archiveItems(records: List<ArchiveRecord>): List<Pair<ManifestEntry, String?>> = records.map { r ->
            ManifestEntry(r.name, r.bytes, HivePartition.of(r.name), FileSyncState.SYNCED) to r.station
        }
    }
}

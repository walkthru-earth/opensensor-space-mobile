package earth.walkthru.opensensor.files

import earth.walkthru.aqsync.archive.ArchiveExport
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ExportEntry
import earth.walkthru.aqsync.archive.ExportRun
import earth.walkthru.aqsync.archive.ExportSpace
import earth.walkthru.aqsync.archive.StagedFile
import earth.walkthru.aqsync.data.sync.FileSyncState
import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import earth.walkthru.aqsync.storage.StorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
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
 * signal and a target may still be reading, so the next export removes the run instead.
 */
interface ExportPresenter {
    suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean

    suspend fun save(file: StagedFile): Boolean
}

/** One-off notices for the snackbar. */
enum class FilesMessage { NO_DAILY, EXPORT_FAILED, NO_SPACE }

data class FilesState(
    /** Every verified file on this phone, of every station. */
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
 * stages every export through `:core:archive` ([ArchiveExport]) into a fresh run of [space]. Exports
 * only read the archive, a staged copy is the only thing they write, and the run is removed once the
 * sheet closed (B7).
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

    val state: StateFlow<FilesState> = combine(index.observeAll(), owner, exporting) { records, o, busy ->
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

    fun saveZip(presenter: ExportPresenter) = run(presenter) {
        val zip = archiveZip() ?: return@run null
        removingRun(zip.run) { it.save(zip) }
    }

    fun shareDaily(presenter: ExportPresenter) = run(presenter) {
        val list = daily ?: return@run null
        var zip: StagedFile? = null
        holdDaily {
            val entries = withContext(io) { ArchiveExport.dailyEntries(list()) }
            if (entries != null) zip = withContext(io) { ArchiveExport.stageZip(space, entries, ArchiveExport.dailyZipName(now())) }
        }
        val staged = zip
        if (staged == null) {
            _messages.tryEmit(FilesMessage.NO_DAILY)
            return@run null
        }
        present(it, listOf(staged), ShareKind.ARCHIVE)
    }

    private suspend fun <T> stage(block: suspend () -> T): T = withContext(io) { block() }

    private suspend fun archiveZip(): StagedFile? = withContext(io) {
        val entries = ArchiveExport.archiveEntries(store, state.value.shown, now())
        if (entries.isEmpty()) null else ArchiveExport.stageZip(space, entries, ArchiveExport.archiveZipName(now()))
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
            } catch (_: Exception) {
                _messages.tryEmit(FilesMessage.EXPORT_FAILED)
            } finally {
                exporting.value = false
            }
        }
    }

    /** Shows [files] and removes their run when the sheet says it is safe. */
    private suspend fun present(presenter: ExportPresenter, files: List<StagedFile>, kind: ShareKind): Boolean =
        removingRun(files.firstOrNull()?.run) { presenter.share(files, kind) }

    /**
     * Runs [show] and removes [run] once it returns true. A sheet that fails, or a wait for it that is
     * cancelled, removes the run too, since nothing still reads it and it can be as large as the
     * archive. Only an explicit false (Android's chooser) leaves it for the next export.
     */
    private suspend fun removingRun(run: ExportRun?, show: suspend () -> Boolean): Boolean {
        val done = try {
            show()
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { run?.delete() }
            throw e
        }
        if (done) withContext(io) { run?.delete() }
        return done
    }

    companion object {
        /** The archive alone as list entries, every one synced, for the screen without a device listing. */
        fun archiveItems(records: List<ArchiveRecord>): List<Pair<ManifestEntry, String?>> = records.map { r ->
            ManifestEntry(r.name, r.bytes, HivePartition.of(r.name), FileSyncState.SYNCED) to r.station
        }
    }
}

package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.WindowLostException
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the file currently being pulled. */
data class FileProgress(
    val name: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val resumedFrom: Long,
    /** `READ` windows that arrived incomplete (dropped notifications) and were re-read successfully. */
    val windowsRecovered: Int = 0,
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/** Outcome of one file download. */
sealed interface FileOutcome {
    val name: String

    data class Synced(override val name: String, val bytes: Long, val sha256: String, val alreadyArchived: Boolean = false) : FileOutcome
    data class Mismatch(override val name: String, val reason: String) : FileOutcome
    data class DeviceError(override val name: String, val error: ProtocolError?, val message: String) : FileOutcome
    data class Failed(override val name: String, val message: String) : FileOutcome
}

class SyncAbortedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The per-file half of the phone sync algorithm in docs/ble-sync-protocol.md:
 *
 * ```
 * OPEN name → OPENED handle,size,crc32
 * loop offset < size: READ handle, offset, max_read → CHUNK × n, READ_END next_offset
 * verify size, CRC-32, PAR1 magic; write under original Hive path; record SHA-256
 * CLOSE handle
 * ```
 *
 * Chunks are written at their absolute offsets into a `.partial` file so an interrupted transfer
 * resumes after re-`OPEN` as long as `size` and `crc32` still match.
 *
 * A window that arrives incomplete ([WindowLostException]: notifications were lost between the
 * device and this code) is re-read from the last good offset — `READ` is idempotent on an
 * immutable file — up to [WINDOW_RETRIES] times with a short backoff. Only when the same window
 * keeps failing is the *file* marked [FileOutcome.Failed]; the sync goes on with the next file.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
    private val historyIndexer: HistoryIndexer,
) {
    /** `WINDOW_RETRY_BACKOFF_MS × attempt` between re-reads of a lost window; tests shorten it. */
    var windowRetryBackoffMs: Long = WINDOW_RETRY_BACKOFF_MS

    suspend fun download(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        name: String,
        expectedBytes: Long,
        maxRead: Int,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        if (!SafeName.isValid(name)) return FileOutcome.Failed(name, "refusing unsafe name")

        return archive.withWriterLease(station, name) {
            downloadLocked(session, station, sourceDevice, name, expectedBytes, maxRead, onProgress)
        }
    }

    private suspend fun downloadLocked(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        name: String,
        expectedBytes: Long,
        maxRead: Int,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        val opened = try {
            session.open(name)
        } catch (e: DeviceErrorException) {
            return FileOutcome.DeviceError(name, e.protocolError, e.message ?: "OPEN failed")
        }
        val handle = opened.handle
        try {
            if (opened.size != expectedBytes) {
                return FileOutcome.Mismatch(
                    name,
                    "LIST said $expectedBytes bytes but OPENED says ${opened.size}; the file is supposed to be immutable"
                )
            }
            if (opened.name.isNotEmpty() && opened.name != name) {
                return FileOutcome.Mismatch(name, "OPENED echoed a different name: ${opened.name}")
            }

            // Another collector may have committed while this caller waited for its writer lease.
            // Verify again against OPENED, and never replace immutable history with different bytes.
            val existing = archive.fileFor(station, name)
            if (existing.exists()) {
                if (!existing.isFile) return FileOutcome.Mismatch(name, "archive destination is not a file")
                val verdict = ParquetVerify.verify(existing, opened.size, opened.crc32)
                if (!verdict.ok) return FileOutcome.Mismatch(name, "existing archive conflicts with device: ${verdict.describe()}")
                val sha256 = ParquetVerify.sha256Hex(existing)
                val row = archivedFileDao.get(station, name)
                if (row != null && (row.bytes != opened.size || row.crc32 != opened.crc32 || row.sha256 != sha256)) {
                    return FileOutcome.Mismatch(name, "existing archive conflicts with its recorded fingerprint")
                }
                if (row == null) {
                    archivedFileDao.upsert(
                        historyIndexer.indexed(
                            ArchivedFile(
                                station = station,
                                name = name,
                                bytes = opened.size,
                                crc32 = opened.crc32,
                                sha256 = sha256,
                                syncedAtEpochMs = System.currentTimeMillis(),
                                sourceDevice = sourceDevice,
                            ),
                            existing,
                        )
                    )
                }
                archive.discardPartial(station, name)
                onProgress(FileProgress(name, opened.size, opened.size, opened.size))
                return FileOutcome.Synced(name, opened.size, sha256, alreadyArchived = true)
            }

            var windowsRecovered = 0
            var retriedWithoutPartial = false
            while (true) {
                val (writer, resumeFrom) = archive.openPartial(station, name, opened.size, opened.crc32)
                var offset = resumeFrom
                val progress = FileProgressReporter(name, opened.size, resumeFrom, onProgress)
                progress.report(offset, windowsRecovered)
                writer.use { w ->
                    val readLength = maxRead.coerceAtLeast(1).toLong()
                    var attempt = 0
                    try {
                        while (offset < opened.size) {
                            currentCoroutineContext().ensureActive()
                            val before = offset
                            val end = try {
                                session.read(handle, offset, readLength) { chunk ->
                                    when {
                                        chunk.offset == offset -> {
                                            w.writeAt(chunk.offset, chunk.payload)
                                            offset += chunk.payload.size
                                        }
                                        // Duplicate or overlapping chunk: the bytes are identical (immutable file), keep going.
                                        chunk.offset < offset -> {
                                            val overlap = (offset - chunk.offset).toInt()
                                            if (overlap < chunk.payload.size) {
                                                w.writeAt(offset, chunk.payload.copyOfRange(overlap, chunk.payload.size))
                                                offset = chunk.offset + chunk.payload.size
                                            }
                                        }
                                        // The session checks continuity before calling us; this is a safety net.
                                        else -> throw WindowLostException(offset, "gap in stream: expected offset $offset, got CHUNK at ${chunk.offset}")
                                    }
                                    progress.report(offset, windowsRecovered)
                                }
                            } catch (e: WindowLostException) {
                                attempt++
                                // Bytes written before the gap are good (absolute offsets); the next READ starts
                                // at our own `offset`, which is what is actually on disk (the contract says never
                                // trust the device's count over ours; e.receivedUpTo is the session's view of it).
                                if (attempt > WINDOW_RETRIES) {
                                    return FileOutcome.Failed(
                                        name,
                                        "window at $offset lost $attempt× in a row (last: ${e.message}); " +
                                            "notifications keep getting lost on this link — move closer and retry"
                                    )
                                }
                                delay(windowRetryBackoffMs * attempt)
                                continue
                            }
                            if (!end.ok) {
                                return FileOutcome.DeviceError(
                                    name,
                                    ProtocolError.fromCode(end.status),
                                    "READ ended with status ${end.status}: ${ProtocolError.describe(end.status)}"
                                )
                            }
                            if (offset == before && offset < opened.size) {
                                return FileOutcome.Failed(name, "READ at $offset returned no data before end of file")
                            }
                            if (attempt > 0) {
                                windowsRecovered++
                                attempt = 0
                                progress.report(offset, windowsRecovered)
                            }
                        }
                        w.sync()
                    } finally {
                        // On cancellation or failure, expose the last bytes actually written.
                        progress.report(offset, windowsRecovered, force = true)
                    }
                }

                val verdict = ParquetVerify.verify(writer.file, opened.size, opened.crc32)
                if (!verdict.ok) {
                    archive.discardPartial(station, name)
                    // The metadata records only the device's size and CRC, not a checksum of the
                    // partial prefix. A corrupted prefix can therefore survive resume and fail the
                    // final check. Re-read once from zero while this handle is still open, rather
                    // than making the owner wait for another scheduled or manual sync.
                    if (resumeFrom > 0 && !retriedWithoutPartial) {
                        retriedWithoutPartial = true
                        continue
                    }
                    return FileOutcome.Mismatch(name, verdict.describe())
                }
                val sha256 = ParquetVerify.sha256Hex(writer.file)
                val committed = archive.commit(station, name)
                val row = ArchivedFile(
                    station = station,
                    name = name,
                    bytes = opened.size,
                    crc32 = opened.crc32,
                    sha256 = sha256,
                    syncedAtEpochMs = System.currentTimeMillis(),
                    sourceDevice = sourceDevice,
                )
                // Index the verified bytes now (rows, time span, …) so History never has to re-open the file.
                // A file the reader refuses keeps its row with `indexError`; the archive copy is untouched.
                archivedFileDao.upsert(historyIndexer.indexed(row, committed))
                return FileOutcome.Synced(name, opened.size, sha256)
            }
        } catch (e: DeviceErrorException) {
            return FileOutcome.DeviceError(name, e.protocolError, e.message ?: "device error")
        } finally {
            // Best effort, even when we are being cancelled: the device closes implicitly on the
            // next OPEN or on disconnect anyway, so a failure here is not an error.
            runCatching { withContext(NonCancellable) { withTimeout(3_000) { session.close(handle) } } }
        }
    }

    companion object {
        /** Re-reads of one window before the file is given up; 6 attempts in total. */
        const val WINDOW_RETRIES = 5

        /** Linear backoff: 300 ms, 600 ms, … lets a busy link catch up without stalling the sync. */
        const val WINDOW_RETRY_BACKOFF_MS = 300L
    }
}

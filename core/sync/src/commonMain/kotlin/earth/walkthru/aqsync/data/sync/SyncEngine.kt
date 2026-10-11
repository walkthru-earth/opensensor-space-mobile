package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ArchiveVerifier
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.data.ble.AuthFailedException
import earth.walkthru.aqsync.data.ble.DeviceBusyException
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.WindowLostException
import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.Sha256
import earth.walkthru.aqsync.data.protocol.toHex
import earth.walkthru.aqsync.storage.AlreadyCommitted
import earth.walkthru.aqsync.storage.StorageException
import earth.walkthru.aqsync.sync.nowEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class SyncAbortedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Fills a record's index columns (rows, time span) from the committed file, in the same write as the
 * record itself. Android reads the Parquet footer here. A platform without a history index keeps the
 * record as it is. Never throws: a file the reader refuses gets `indexError` and stays archived.
 */
fun interface ArchiveRecordIndexer {
    suspend fun indexed(record: ArchiveRecord): ArchiveRecord

    companion object {
        /** Stores the receipt columns only. */
        val None: ArchiveRecordIndexer = ArchiveRecordIndexer { it }
    }
}

/**
 * The per-file half of the phone sync algorithm in docs/ble-sync-protocol.md, shared by Android and iOS:
 *
 * ```
 * OPEN name → OPENED handle,size,crc32
 * loop offset < size: READ handle, offset, max_read → CHUNK × n, READ_END next_offset
 * verify size, CRC-32, PAR1 magic; write under original Hive path; record SHA-256
 * CLOSE handle
 * ```
 *
 * Chunks are written at their absolute offsets into the [ArchiveStore] partial so an interrupted
 * transfer resumes after re-`OPEN` as long as `size` and `crc32` still match. A file is synced only
 * after [ArchiveVerifier] passed, the store committed it and the [ArchiveIndex] holds its record.
 *
 * A window that arrives incomplete ([WindowLostException]: notifications were lost between the
 * device and this code) is re-read from the last good offset (`READ` is idempotent on an immutable
 * file) up to [WINDOW_RETRIES] times with a short backoff. Only when the same window keeps failing is
 * the *file* marked [FileOutcome.Failed]. The sync goes on with the next file.
 */
class SyncEngine(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val indexer: ArchiveRecordIndexer = ArchiveRecordIndexer.None,
    /** `windowRetryBackoffMs × attempt` between re-reads of a lost window. Tests shorten it. */
    private val windowRetryBackoffMs: Long = WINDOW_RETRY_BACKOFF_MS,
    private val clock: () -> Long = ::nowEpochMs,
    private val leases: WriterLeases = WriterLeases(),
    /** One line per file this phone failed to store, file name and error class and message only. */
    private val log: (String) -> Unit = {},
    /** Where partial files are written, one file at a time. Tests pass their virtual-time dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        name: String,
        expectedBytes: Long,
        maxRead: Int,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome = download(session, station, sourceDevice, name, expectedBytes, maxRead, onSettled = {}, onProgress = onProgress)

    /**
     * Reports each settled file once, before the cancellable CLOSE. A Synced result means verified
     * bytes are committed and indexed, even when Stop cancels the wait for CLOSED afterwards.
     * This overload keeps existing positional progress callbacks source-compatible.
     */
    suspend fun download(
        session: DeviceSession,
        station: String,
        sourceDevice: String,
        name: String,
        expectedBytes: Long,
        maxRead: Int,
        onSettled: (FileOutcome) -> Unit,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        if (!ArchiveLayout.isArchiveName(name)) return FileOutcome.Failed(name, "refusing unsafe name").also(onSettled)
        // An unsafe station is a caller bug (the device identity was validated), so it throws.
        val key = ArchiveKey(station, name)
        return leases.withLease(key) { downloadLocked(session, key, sourceDevice, expectedBytes, maxRead, onSettled, onProgress) }
    }

    private suspend fun downloadLocked(
        session: DeviceSession,
        key: ArchiveKey,
        sourceDevice: String,
        expectedBytes: Long,
        maxRead: Int,
        onSettled: (FileOutcome) -> Unit,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        val name = key.name
        val opened = try {
            session.open(name)
        } catch (e: DeviceErrorException) {
            if (e.endsSession) throw e
            return FileOutcome.DeviceError(name, e.protocolError, e.message ?: "OPEN failed").also(onSettled)
        }
        val handle = opened.handle
        if (opened.size != expectedBytes || (opened.name.isNotEmpty() && opened.name != name)) {
            // Nothing was read, so the handle is closed like any finished file.
            val outcome = if (opened.size != expectedBytes) {
                FileOutcome.Mismatch(name, "LIST said $expectedBytes bytes but OPENED says ${opened.size}; the file is supposed to be immutable")
            } else {
                FileOutcome.Mismatch(name, "OPENED echoed a different name: ${opened.name}")
            }
            try {
                onSettled(outcome)
                return outcome
            } finally {
                closeHandle(session, handle)
            }
        }
        // Set once the file reached an outcome with the link still usable. Anything else (Stop, a
        // lost link, a session error) leaves the CLOSE to the link, so nothing waits for it here.
        var settled = false
        try {
            val outcome = try {
                transfer(session, key, opened, sourceDevice, maxRead, onProgress)
            } catch (e: StorageFault) {
                // This phone's archive failed for this file. The link and the session are fine, so the
                // collector decides whether the next file runs. Only a StorageException message is known
                // to be free of paths and station ids, so any other cause reports its class alone.
                val cause = e.cause ?: e
                val reason = if (cause is StorageException) "storage ${cause::class.simpleName}: ${cause.message}" else "storage ${cause::class.simpleName}"
                log("$name not saved, $reason")
                FileOutcome.Failed(name, reason, storage = true)
            } catch (e: DeviceErrorException) {
                if (e.endsSession) throw e
                FileOutcome.DeviceError(name, e.protocolError, e.message ?: "device error")
            }
            settled = true
            onSettled(outcome)
            return outcome
        } finally {
            if (settled) closeHandle(session, handle) else session.oweClose(handle)
        }
    }

    /**
     * `CLOSE` as a regular request. Cancelled before it was written, the link owes it. Once written,
     * the link drains its reply. The device also closes on the next `OPEN` or on disconnect.
     */
    private suspend fun closeHandle(session: DeviceSession, handle: Int) {
        try {
            session.close(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("CLOSE $handle not confirmed, ${e::class.simpleName}")
        }
    }

    private suspend fun transfer(
        session: DeviceSession,
        key: ArchiveKey,
        opened: Frame.Opened,
        sourceDevice: String,
        maxRead: Int,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        val name = key.name
        val handle = opened.handle
        val meta = PartialMeta(opened.size, opened.crc32)

        // Another collector may have committed while this caller waited for its writer lease.
        // Verify again against OPENED, and never replace immutable history with different bytes.
        if (storage { store.exists(key) }) return storage { adoptExisting(key, meta, sourceDevice, onProgress) }

        var windowsRecovered = 0
        var retriedWithoutPartial = false
        while (true) {
            val partial = storage { store.openPartial(key, meta) }
            val resumeFrom = partial.resumeFrom
            val progress = FileProgressReporter(name, opened.size, resumeFrom, onProgress)
            progress.report(resumeFrom, windowsRecovered)
            val failed = coroutineScope {
                val writer = ArchiveWriter(partial, this, io)
                try {
                    readAll(session, name, handle, opened.size, maxRead, writer, progress, windowsRecovered) { windowsRecovered = it }
                        ?.let { return@coroutineScope it }
                    storage { writer.finish() }
                    null
                } finally {
                    writer.abandon()
                    // On Stop or failure, show the bytes actually on disk, which is where resume starts.
                    progress.report(writer.written, windowsRecovered, force = true)
                }
            }
            if (failed != null) return failed

            val (verdict, sha256) = storage { store.reader(key, partial = true).use { verifyAndHash(it, meta) } }
            if (!verdict.ok) {
                storage { store.discardPartial(key) }
                // The meta records only the device's size and CRC, not a checksum of the partial
                // prefix. A corrupted prefix can therefore survive resume and fail the final check.
                // Re-read once from zero while this handle is still open, rather than making the
                // owner wait for another scheduled or manual sync.
                if (resumeFrom > 0 && !retriedWithoutPartial) {
                    retriedWithoutPartial = true
                    continue
                }
                return FileOutcome.Mismatch(name, verdict.describe())
            }
            // A final that appeared since the check above (another process, or a crash after an
            // earlier commit) is never replaced. The waiter's path re-checks it against OPENED.
            val committed = storage {
                try {
                    store.commit(key)
                    true
                } catch (_: AlreadyCommitted) {
                    false
                }
            }
            if (!committed) return storage { adoptExisting(key, meta, sourceDevice, onProgress) }
            val record = ArchiveRecord(
                station = key.station,
                name = name,
                bytes = opened.size,
                crc32 = opened.crc32,
                sha256 = sha256,
                syncedAtEpochMs = clock(),
                sourceDevice = sourceDevice,
            )
            // Index the verified bytes now (rows, time span, …) so History never has to re-open the file.
            // A file the reader refuses keeps its record with `indexError`. The archive copy is untouched.
            storage { index.put(indexer.indexed(record)) }
            return FileOutcome.Synced(name, opened.size, sha256)
        }
    }

    /**
     * `READ` windows from the writer's resume point to [size]. Each chunk is only queued for
     * [writer], so the frames keep flowing while the disk works. Null when every byte was queued,
     * or the outcome that ends this file.
     */
    private suspend fun readAll(
        session: DeviceSession,
        name: String,
        handle: Int,
        size: Long,
        maxRead: Int,
        writer: ArchiveWriter,
        progress: FileProgressReporter,
        recoveredBefore: Int,
        recovered: (Int) -> Unit,
    ): FileOutcome? {
        var offset = writer.written
        var windowsRecovered = recoveredBefore
        val readLength = maxRead.coerceAtLeast(1).toLong()
        var attempt = 0
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val before = offset
            val end = try {
                session.read(handle, offset, readLength) { chunk ->
                    when {
                        chunk.offset == offset -> {
                            writer.write(chunk.offset, chunk.payload)
                            offset += chunk.payload.size
                        }
                        // Duplicate or overlapping chunk: the bytes are identical (immutable file), keep going.
                        chunk.offset < offset -> {
                            val overlap = (offset - chunk.offset).toInt()
                            if (overlap < chunk.payload.size) {
                                writer.write(offset, chunk.payload.copyOfRange(overlap, chunk.payload.size))
                                offset = chunk.offset + chunk.payload.size
                            }
                        }
                        // The session checks continuity before calling us. This is a safety net.
                        else -> throw WindowLostException(offset, "gap in stream: expected offset $offset, got CHUNK at ${chunk.offset}")
                    }
                    progress.report(offset, windowsRecovered)
                }
            } catch (e: WindowLostException) {
                attempt++
                // Bytes queued before the gap are good (absolute offsets). The next READ starts at
                // our own `offset`, never the device's count (e.receivedUpTo is the session's view of it).
                if (WindowRetryPolicy.givesUp(attempt)) {
                    return FileOutcome.Failed(
                        name,
                        "window at $offset lost $attempt× in a row (last: ${e.message}); " +
                            "notifications keep getting lost on this link, move closer and retry"
                    )
                }
                delay(WindowRetryPolicy.backoffMs(attempt, windowRetryBackoffMs))
                continue
            }
            if (!end.ok) {
                return FileOutcome.DeviceError(
                    name,
                    ProtocolError.fromCode(end.status),
                    "READ ended with status ${end.status}: ${ProtocolError.describe(end.status)}"
                )
            }
            if (offset == before && offset < size) {
                return FileOutcome.Failed(name, "READ at $offset returned no data before end of file")
            }
            if (attempt > 0) {
                windowsRecovered++
                recovered(windowsRecovered)
                attempt = 0
                progress.report(offset, windowsRecovered)
            }
        }
        return null
    }

    /** The waiter's path: the file was committed while it waited. Rechecks the bytes and the record. */
    private suspend fun adoptExisting(
        key: ArchiveKey,
        meta: PartialMeta,
        sourceDevice: String,
        onProgress: (FileProgress) -> Unit,
    ): FileOutcome {
        val name = key.name
        // A destination that cannot be opened (a directory, say) is this phone's storage fault, not a
        // conflict with the device: the caller's storage wrapper fails this file and keeps the partial.
        val (verdict, sha256) = store.reader(key).use { verifyAndHash(it, meta) }
        if (!verdict.ok) return FileOutcome.Mismatch(name, "existing archive conflicts with device: ${verdict.describe()}")
        val row = index.get(key)
        // An older iOS receipt holds size and CRC only. Its empty fingerprint is filled, never compared.
        val knownSha = row?.sha256?.takeIf { it.isNotEmpty() }
        if (row != null && (row.bytes != meta.size || row.crc32 != meta.crc32 || (knownSha != null && knownSha != sha256))) {
            return FileOutcome.Mismatch(name, "existing archive conflicts with its recorded fingerprint")
        }
        if (row != null && knownSha == null) {
            index.put(row.copy(sha256 = sha256))
        } else if (row == null) {
            index.put(
                indexer.indexed(
                    ArchiveRecord(
                        station = key.station,
                        name = name,
                        bytes = meta.size,
                        crc32 = meta.crc32,
                        sha256 = sha256,
                        syncedAtEpochMs = clock(),
                        sourceDevice = sourceDevice,
                    )
                )
            )
        }
        store.discardPartial(key)
        onProgress(FileProgress(name, meta.size, meta.size, meta.size))
        return FileOutcome.Synced(name, meta.size, sha256, alreadyArchived = true)
    }

    /** Size, CRC-32 and `PAR1` in one streamed pass, with the SHA-256 of the same bytes. */
    private fun verifyAndHash(reader: ByteReader, meta: PartialMeta): Pair<ParquetVerify.Result, String> {
        val sha = Sha256()
        val verdict = ArchiveVerifier.verify(reader, meta) { bytes, n -> sha.update(bytes, 0, n) }
        val hex = sha.digest().toHex()
        return verdict to hex
    }

    /** Runs one [ArchiveStore] or [ArchiveIndex] call. Its failure becomes a [StorageFault] for this file. */
    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageFault) {
        throw e
    } catch (e: Exception) {
        throw StorageFault(e)
    }

    /**
     * An error about the session, not the file: the LAN token was refused, the sensor is busy, or an
     * `ERROR` for op 0. Every later file would fail the same way, so the run ends with it instead.
     */
    private val DeviceErrorException.endsSession: Boolean
        get() = this is AuthFailedException || this is DeviceBusyException || frame.op == 0

    companion object {
        /** Re-reads of one window before the file is given up ([WindowRetryPolicy.RETRIES]). */
        const val WINDOW_RETRIES = WindowRetryPolicy.RETRIES

        /** Base of the linear backoff between re-reads ([WindowRetryPolicy.BACKOFF_MS]). */
        const val WINDOW_RETRY_BACKOFF_MS = WindowRetryPolicy.BACKOFF_MS
    }
}

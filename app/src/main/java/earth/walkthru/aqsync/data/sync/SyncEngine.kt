package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.ProtocolViolationException
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/** Outcome of one file download. */
sealed interface FileOutcome {
    val name: String

    data class Synced(override val name: String, val bytes: Long, val sha256: String) : FileOutcome
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
 */
@Singleton
class SyncEngine @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
) {
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

        val opened = try {
            session.open(name)
        } catch (e: DeviceErrorException) {
            return FileOutcome.DeviceError(name, e.protocolError, e.message ?: "OPEN failed")
        }
        val handle = opened.handle
        try {
            if (opened.size != expectedBytes) {
                return FileOutcome.Mismatch(
                    name, "LIST said $expectedBytes bytes but OPENED says ${opened.size}; the file is supposed to be immutable"
                )
            }
            if (opened.name.isNotEmpty() && opened.name != name) {
                return FileOutcome.Mismatch(name, "OPENED echoed a different name: ${opened.name}")
            }

            val (writer, resumeFrom) = archive.openPartial(station, name, opened.size, opened.crc32)
            var offset = resumeFrom
            onProgress(FileProgress(name, offset, opened.size, resumeFrom))

            writer.use { w ->
                val readLength = maxRead.coerceAtLeast(1).toLong()
                while (offset < opened.size) {
                    currentCoroutineContext().ensureActive()
                    val before = offset
                    val end = session.read(handle, offset, readLength) { chunk ->
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
                            else -> throw ProtocolViolationException(
                                "gap in stream: expected offset $offset, got CHUNK at ${chunk.offset}"
                            )
                        }
                        onProgress(FileProgress(name, offset, opened.size, resumeFrom))
                    }
                    if (!end.ok) {
                        return FileOutcome.DeviceError(
                            name, ProtocolError.fromCode(end.status),
                            "READ ended with status ${end.status}: ${ProtocolError.describe(end.status)}"
                        )
                    }
                    if (end.nextOffset != offset) {
                        throw ProtocolViolationException(
                            "READ_END next_offset=${end.nextOffset} but ${offset} bytes were received"
                        )
                    }
                    if (offset == before && offset < opened.size) {
                        throw ProtocolViolationException("READ at $offset returned no data before end of file")
                    }
                }
                w.sync()
            }

            val verdict = ParquetVerify.verify(writer.file, opened.size, opened.crc32)
            if (!verdict.ok) {
                archive.discardPartial(station, name)
                return FileOutcome.Mismatch(name, verdict.describe())
            }
            val sha256 = ParquetVerify.sha256Hex(writer.file)
            archive.commit(station, name)
            archivedFileDao.upsert(
                ArchivedFile(
                    station = station,
                    name = name,
                    bytes = opened.size,
                    crc32 = opened.crc32,
                    sha256 = sha256,
                    syncedAtEpochMs = System.currentTimeMillis(),
                    sourceDevice = sourceDevice,
                )
            )
            return FileOutcome.Synced(name, opened.size, sha256)
        } catch (e: DeviceErrorException) {
            return FileOutcome.DeviceError(name, e.protocolError, e.message ?: "device error")
        } finally {
            // Best effort, even when we are being cancelled: the device closes implicitly on the
            // next OPEN or on disconnect anyway, so a failure here is not an error.
            runCatching { withContext(NonCancellable) { withTimeout(3_000) { session.close(handle) } } }
        }
    }
}

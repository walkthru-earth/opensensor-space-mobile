package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.ProtocolError

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

    /**
     * The transfer did not finish. With [storage], this phone could not write, move or record the
     * file (disk full, a refused path, a failed commit or receipt). The next file still runs.
     */
    data class Failed(override val name: String, val message: String, val storage: Boolean = false) : FileOutcome
}

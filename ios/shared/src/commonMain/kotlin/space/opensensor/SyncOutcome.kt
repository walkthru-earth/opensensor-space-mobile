package space.opensensor

import space.opensensor.collection.SyncEndReason
import space.opensensor.data.sync.FileNotSaved
import space.opensensor.strings.UiText
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.companion_lan_sync_paused
import space.opensensor.strings.resources.companion_sync_storage_failed
import space.opensensor.strings.resources.msg_busy
import space.opensensor.strings.resources.msg_hardware_in_use
import space.opensensor.strings.resources.msg_sync_aborted
import space.opensensor.strings.resources.msg_sync_finished
import space.opensensor.strings.resources.msg_sync_stopped
import space.opensensor.strings.resources.msg_token_needs_ble
import space.opensensor.strings.resources.msg_up_to_date
import space.opensensor.strings.resources.sync_some_failed

/** How one sync ended. [SyncResult] carries the counts. */
enum class SyncEnd {
    /** Every listed file is on this phone. */
    COMPLETE,

    /** Some files were not saved. Each failed alone and the rest were saved. */
    FILES_NOT_SAVED,

    /** This phone could not write, move or record some files. The rest were saved and the link stays. */
    STORAGE_FAILED,

    /** Cancelled through `SyncHandle.cancel`. Partial files resume next time. */
    STOPPED,

    /** The socket or the peripheral went away, or the sensor stopped answering. Partials resume. */
    LINK_LOST,

    /** The sensor refused the LAN token (`ERROR` 14). */
    AUTH_FAILED,

    /** The sensor is busy (`ERROR` 7). */
    BUSY,

    /** This sensor is already syncing over the other link on this phone. Nothing was sent. */
    IN_USE,

    /** A protocol fault, or a storage fault outside one file, ended the sync. Saved files and the link stay. */
    FAILED,
}

/**
 * The result of one sync. [files] is how many the sensor listed, [saved] how many this sync
 * copied, and [notSaved] how many failed alone.
 */
class SyncResult internal constructor(
    val end: SyncEnd,
    val files: Int,
    val saved: Int,
    val notSaved: Int,
    /** Per-file failures for retry and diagnostics; never includes file contents. */
    val failures: List<FileNotSaved> = emptyList(),
    /**
     * Why the run ended in the shared terms Android uses, from the typed cause when one is known.
     * [end] stays the coarse class the native adapters branch on.
     */
    val endReason: SyncEndReason = end.reason,
) {
    /** The link is still usable afterwards, so the session can stay open. */
    val keepsLink: Boolean get() = when (end) {
        SyncEnd.COMPLETE, SyncEnd.FILES_NOT_SAVED, SyncEnd.STORAGE_FAILED, SyncEnd.IN_USE, SyncEnd.FAILED -> true
        SyncEnd.STOPPED, SyncEnd.LINK_LOST, SyncEnd.AUTH_FAILED, SyncEnd.BUSY -> false
    }

    /**
     * The sensor refused or rotated the LAN token, so the stored one is dropped and the next
     * Bluetooth connection fetches a new one. The same causes `LanTokenPolicy.invalidatesToken`
     * names, read from [endReason]. A broken Bluetooth bond is not one of them.
     */
    val dropsLanToken: Boolean
        get() = endReason == SyncEndReason.TOKEN_REJECTED || endReason == SyncEndReason.TOKEN_ROTATED

    /** Worth resuming automatically when the link returns. */
    val resumable: Boolean get() = end == SyncEnd.LINK_LOST || endReason == SyncEndReason.INTERRUPTED

    /** The words for this outcome, Android's keys and wording. */
    internal val message: UiText
        get() = if (endReason == SyncEndReason.INTERRUPTED) {
            UiText.of(Res.string.companion_lan_sync_paused)
        } else {
            when (end) {
                SyncEnd.COMPLETE, SyncEnd.FILES_NOT_SAVED -> when {
                    notSaved > 0 -> UiText.plural(Res.plurals.sync_some_failed, notSaved, notSaved)
                    saved == 0 -> UiText.of(Res.string.msg_up_to_date)
                    else -> UiText.plural(Res.plurals.msg_sync_finished, saved, saved, saved)
                }
                SyncEnd.STORAGE_FAILED -> UiText.of(Res.string.companion_sync_storage_failed)
                SyncEnd.STOPPED -> UiText.of(Res.string.msg_sync_stopped)
                SyncEnd.AUTH_FAILED -> UiText.of(Res.string.msg_token_needs_ble)
                SyncEnd.BUSY -> UiText.of(Res.string.msg_busy)
                SyncEnd.IN_USE -> UiText.of(Res.string.msg_hardware_in_use)
                SyncEnd.LINK_LOST, SyncEnd.FAILED -> UiText.of(Res.string.msg_sync_aborted)
            }
        }

    override fun toString(): String = "SyncResult($end, $endReason, files=$files, saved=$saved, notSaved=$notSaved)"
}

/** The shared reason for an ending when no typed cause is known. */
internal val SyncEnd.reason: SyncEndReason
    get() = when (this) {
        SyncEnd.COMPLETE, SyncEnd.FILES_NOT_SAVED -> SyncEndReason.COMPLETED
        SyncEnd.STORAGE_FAILED -> SyncEndReason.STORAGE_ERROR
        SyncEnd.STOPPED -> SyncEndReason.STOPPED
        SyncEnd.LINK_LOST -> SyncEndReason.LINK_LOST
        SyncEnd.AUTH_FAILED -> SyncEndReason.TOKEN_REJECTED
        SyncEnd.BUSY -> SyncEndReason.DEVICE_BUSY
        SyncEnd.IN_USE, SyncEnd.FAILED -> SyncEndReason.OTHER
    }

/** Progress of one sync, throttled for the screen. */
class SyncProgress internal constructor(
    val filesDone: Int,
    val filesTotal: Int,
    /** Bytes of the file in transfer that this phone holds. */
    val fileBytes: Long,
    /** Bytes this sync received, without resumed bytes. */
    val receivedBytes: Long,
    val bytesPerSecond: Double,
    val bytesTotal: Long = 0,
)

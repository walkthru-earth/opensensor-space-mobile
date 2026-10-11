package earth.walkthru.aqsync.data.sync

/** How the last sync run ended, kept on the progress state so the link watcher can decide about a resume. */
enum class SyncEndCause {
    /** Every planned file got an outcome (synced, mismatch, device `ERROR`, failed). */
    COMPLETED,

    /** The user pressed Stop (or the session was closed on purpose). */
    STOPPED,

    /** The pipe failed under the transfer: no response, link lost, socket error, transport closed. */
    TRANSPORT_FAILURE,

    /** Anything else (protocol violation, I/O on the phone, a bug). */
    FAILURE,
}

/**
 * Whether a sync should be restarted once a lost link has been re-established automatically.
 *
 * Measured on the OnePlus 7 Pro (2026-09-17): the device stopped answering mid-sync, the sync
 * ended with `TransportException("no response from device within 60 s")`, and the link was
 * declared lost a moment later. The reconnect succeeded but ran with `resumeSync = false` because
 * the sync was no longer `running`, so the user had to press Sync all again. A sync that died of
 * the link is the same situation as a sync interrupted by the link — resume it. A user Stop or a
 * run that completed (even with device `ERROR`s) is left alone.
 *
 * Pure so it can be unit-tested; `DeviceConnectionRepository` in `app/` feeds it the progress snapshot taken at the moment the link was lost.
 */
object SyncResumePolicy {
    /**
     * A transport-level failure older than this is not tied to the link loss we are now seeing:
     * the user may have done other things since. Covers the LAN heartbeat's 180 s receive deadline
     * with margin.
     */
    const val RESUME_WINDOW_MS = 5 * 60_000L

    fun shouldResume(running: Boolean, endCause: SyncEndCause?, finishedAtEpochMs: Long?, nowEpochMs: Long): Boolean {
        if (running) return true
        if (endCause != SyncEndCause.TRANSPORT_FAILURE) return false
        val finished = finishedAtEpochMs ?: return false
        val age = nowEpochMs - finished
        return age in 0..RESUME_WINDOW_MS
    }
}

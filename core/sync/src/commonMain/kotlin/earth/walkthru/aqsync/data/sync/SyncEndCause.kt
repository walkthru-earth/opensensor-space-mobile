package earth.walkthru.aqsync.data.sync

/** How the last sync run ended, kept on the progress state. */
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

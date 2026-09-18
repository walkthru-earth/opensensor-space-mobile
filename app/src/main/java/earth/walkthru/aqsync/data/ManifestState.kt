package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.protocol.Frame

/**
 * The device's file listing (`LIST` → `FILE` × n → `LIST_END`) as the app knows it.
 *
 * A refresh never throws the previous listing away: [Loading] and [Error] carry the last
 * [Loaded] in [last], so the Files screen keeps showing the list (with an age and, on failure, a
 * one-line reason) and [DeviceConnectionRepository.plan] stays usable. Before this, a post-sync
 * `LIST` that died with "link lost while waiting for a response" replaced the whole list with a red
 * banner right after a successful sync, and the plan went null. The list is dropped only on
 * [Idle] — a new session, [DeviceConnectionRepository.resetDerivedState].
 */
sealed interface ManifestState {
    /** The most recent successful listing, or null if there has been none in this session. */
    val last: Loaded?

    data object Idle : ManifestState {
        override val last: Loaded? get() = null
    }

    /** A `LIST` is in flight; [last] is what the screen keeps showing meanwhile. */
    data class Loading(override val last: Loaded? = null) : ManifestState

    data class Loaded(val files: List<Frame.File>, val end: Frame.ListEnd, val atEpochMs: Long) : ManifestState {
        override val last: Loaded get() = this
    }

    /** The most recent `LIST` failed with [message]; [last] is the listing that remains valid until the next success. */
    data class Error(val message: String, override val last: Loaded? = null) : ManifestState

    /** True while a `LIST` request is in flight. */
    val isLoading: Boolean get() = this is Loading

    /** The failure of the most recent refresh, if it failed; null after a success or before any attempt. */
    val error: String? get() = (this as? Error)?.message

    /** Transition: a refresh starts. The previous listing is kept. */
    fun refreshing(): ManifestState = Loading(last)

    /** Transition: the refresh failed. The previous listing is kept. */
    fun failed(message: String): ManifestState = Error(message, last)

    /** Transition: the refresh succeeded. */
    fun loaded(files: List<Frame.File>, end: Frame.ListEnd, atEpochMs: Long): ManifestState = Loaded(files, end, atEpochMs)
}

package space.opensensor.data

import space.opensensor.data.protocol.Frame
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.SyncPlan

/**
 * The device's file listing (`LIST` → `FILE` × n → `LIST_END`) as the app knows it.
 *
 * A refresh never throws the previous listing away: [Loading] and [Error] carry the last
 * [Loaded] in [last], so the Files screen keeps showing the list (with an age and, on failure, a
 * one-line reason) and `DeviceFacade.plan` stays available for display. Retained
 * listings cannot establish that every device file is synced: explicit whole-device sync takes a
 * fresh complete LIST and plans directly from its result. Before this, a post-sync
 * `LIST` that died with "link lost while waiting for a response" replaced the whole list with a red
 * banner right after a successful sync, and the plan went null. The list is dropped only on
 * [Idle] — a new session, `DeviceLinkOwner.resetDerivedState`.
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

    /** The most recent `LIST` failed with [message]; [last] remains visible as a previous snapshot. */
    data class Error(val message: String, override val last: Loaded? = null) : ManifestState

    /** True while a `LIST` request is in flight. */
    val isLoading: Boolean get() = this is Loading

    /** The failure of the most recent refresh, if it failed; null after a success or before any attempt. */
    val error: String? get() = (this as? Error)?.message

    /** A success banner requires verified or user removed entries from this exact successful listing. */
    fun confirmsAllSynced(plan: SyncPlan?): Boolean {
        if (this !is Loaded || plan == null || files.isEmpty() || files.size != plan.entries.size) return false
        // SyncPlanner preserves FILE order. Compare directly without allocating a second index;
        // manifest and derived plan flows may reach a screen on different coroutine turns.
        return files.indices.all { index ->
            val file = files[index]
            val entry = plan.entries[index]
            (entry.state == FileSyncState.SYNCED || entry.state == FileSyncState.REMOVED) && entry.name == file.name && entry.bytes == file.bytes
        }
    }

    /** Transition: a refresh starts. The previous listing is kept. */
    fun refreshing(): ManifestState = Loading(last)

    /** Transition: the refresh failed. The previous listing is kept. */
    fun failed(message: String): ManifestState = Error(message, last)

    /** Transition: the refresh succeeded. */
    fun loaded(files: List<Frame.File>, end: Frame.ListEnd, atEpochMs: Long): ManifestState = Loaded(files, end, atEpochMs)

    /**
     * Transition: an incremental `LIST_SINCE` named only [files], the new ones. They join [last] in
     * place by name, the card counters come from [end] and the age stays that of the last whole
     * listing, which alone shows what the device dropped. Without a [last] there is no listing to
     * show, so the state is [Idle].
     */
    fun withNew(files: List<Frame.File>, end: Frame.ListEnd): ManifestState {
        val base = last ?: return Idle
        val fresh = files.associateBy { it.name }
        val kept = base.files.map { fresh[it.name] ?: it }
        val known = base.files.mapTo(HashSet()) { it.name }
        val merged = kept + files.filter { it.name !in known }
        return Loaded(merged, Frame.ListEnd(merged.size.toLong(), base.end.partials, end.sdKib, end.sdUsedKib), base.atEpochMs)
    }
}

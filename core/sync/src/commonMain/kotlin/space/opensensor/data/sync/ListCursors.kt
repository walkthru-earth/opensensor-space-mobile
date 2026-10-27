package space.opensensor.data.sync

import space.opensensor.data.protocol.ListCursor

/**
 * The saved `LIST_SINCE` state of one station on this phone. [cursor] is stored as the board sent
 * it. [wholeAtEpochMs] is when the last whole listing settled and [sinceWhole] counts the
 * incremental runs settled after it, so the collector knows when the audit is due.
 */
data class ListCursorState(
    val thing: String,
    val cursor: ListCursor,
    val wholeAtEpochMs: Long,
    val sinceWhole: Int,
)

/**
 * The phone's `LIST_SINCE` cursors, one per station. A cursor only ever goes back to the board it
 * came from. Both phones keep them in the Room `list_cursor` table. A station forgotten with its
 * files keeps its row, and the next run still sends the zero cursor because the phone holds no file of it.
 */
interface ListCursors {
    suspend fun get(thing: String): ListCursorState?

    /** Adds or replaces the cursor of [ListCursorState.thing]. */
    suspend fun put(state: ListCursorState)

    /** No cursors, so every collection takes a whole listing. */
    object None : ListCursors {
        override suspend fun get(thing: String): ListCursorState? = null
        override suspend fun put(state: ListCursorState) = Unit
    }
}

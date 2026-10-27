package space.opensensor.data.archive

import space.opensensor.data.local.database.ListCursorDao
import space.opensensor.data.local.database.ListCursorEntity
import space.opensensor.data.protocol.ListCursor
import space.opensensor.data.sync.ListCursorState
import space.opensensor.data.sync.ListCursors

/** The shared [ListCursors] over the `list_cursor` table, on both phones. A row of the wrong size reads as none. */
class RoomListCursors(private val dao: ListCursorDao) : ListCursors {
    override suspend fun get(thing: String): ListCursorState? {
        val row = dao.get(thing) ?: return null
        if (row.cursor.size != ListCursor.BYTES) return null
        return ListCursorState(row.thing, ListCursor(row.cursor), row.wholeAtEpochMs, row.sinceWhole)
    }

    override suspend fun put(state: ListCursorState) =
        dao.upsert(ListCursorEntity(state.thing, state.cursor.bytes, state.wholeAtEpochMs, state.sinceWhole))
}

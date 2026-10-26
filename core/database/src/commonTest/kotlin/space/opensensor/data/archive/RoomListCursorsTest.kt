package space.opensensor.data.archive

import kotlinx.coroutines.test.runTest
import space.opensensor.data.local.database.ListCursorEntity
import space.opensensor.data.local.database.inMemoryAppDatabase
import space.opensensor.data.protocol.ListCursor
import space.opensensor.data.sync.ListCursorState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [RoomListCursors] over the `list_cursor` table, on the Android host and the iOS simulator. */
class RoomListCursorsTest {
    private val db = inMemoryAppDatabase()
    private val cursors = RoomListCursors(db.listCursorDao())
    private val cursor = ListCursor(ByteArray(ListCursor.BYTES) { (it * 7).toByte() })

    @AfterTest
    fun close() = db.close()

    @Test
    fun putReplacesTheCursorOfItsStationAndKeepsTheBytes() = runTest {
        val first = ListCursorState("thing-a", ListCursor.ZERO, 10, 0)
        cursors.put(first)
        cursors.put(ListCursorState("thing-b", ListCursor.ZERO, 1, 3))
        cursors.put(first.copy(cursor = cursor, sinceWhole = 4))

        assertEquals(first.copy(cursor = cursor, sinceWhole = 4), cursors.get("thing-a"))
        assertEquals(ListCursorState("thing-b", ListCursor.ZERO, 1, 3), cursors.get("thing-b"))
        assertNull(cursors.get("thing-c"))
    }

    @Test
    fun removeForgetsOneStation() = runTest {
        cursors.put(ListCursorState("thing-a", cursor, 10, 0))
        cursors.put(ListCursorState("thing-b", cursor, 10, 0))

        cursors.remove("thing-a")
        assertNull(cursors.get("thing-a"))
        assertEquals(cursor, cursors.get("thing-b")?.cursor)
    }

    @Test
    fun aRowOfTheWrongSizeReadsAsNone() = runTest {
        db.listCursorDao().upsert(ListCursorEntity("thing-a", ByteArray(31), 10, 0))
        assertNull(cursors.get("thing-a"))
    }
}

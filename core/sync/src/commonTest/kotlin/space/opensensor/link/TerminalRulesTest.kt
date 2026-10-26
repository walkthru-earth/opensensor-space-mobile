package space.opensensor.link

import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode
import space.opensensor.link.Routing.CONTINUE
import space.opensensor.link.Routing.FOREIGN
import space.opensensor.link.Routing.TERMINAL
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalRulesTest {
    private val file = Frame.File(1, "a.parquet")
    private val listEnd = Frame.ListEnd(1, 0, 0, 0)
    private val opened = Frame.Opened(1, 10, 0, "a.parquet")
    private val chunk = Frame.Chunk(1, 0, byteArrayOf(1))
    private val readEnd = Frame.ReadEnd(1, 1, 0)
    private val closed = Frame.Closed(1)
    private val timeSet = Frame.TimeSet(1, 1, null)
    private val flushed = Frame.Flushed(1, 1)
    private val config = Frame.Config(0, "{}")
    private val wifiAp = Frame.WifiAp(-50, 3, 6, "net")
    private val scanEnd = Frame.WifiScanEnd(1, 0)
    private val rebooting = Frame.Rebooting(500)
    private val logLine = Frame.Log(byteArrayOf(65))
    private val logEnd = Frame.LogEnd(1, 1)
    private val token = Frame.Token(4242, ByteArray(32))
    private val diag = Frame.Diag(1, List(17) { 0 }, emptyList())
    private val aborted = Frame.Aborted(0)

    private val all = listOf(
        file, listEnd, opened, chunk, readEnd, closed, timeSet, flushed, config, wifiAp, scanEnd,
        rebooting, logLine, logEnd, token, diag, aborted,
    )

    /** Each opcode with the frames that continue its reply and the frame that ends it. */
    private val table: Map<Int, Pair<List<Frame>, Frame?>> = mapOf(
        Opcode.LIST to (listOf<Frame>(file) to listEnd),
        Opcode.OPEN to (emptyList<Frame>() to opened),
        Opcode.READ to (listOf<Frame>(chunk) to readEnd),
        Opcode.CLOSE to (emptyList<Frame>() to closed),
        Opcode.SET_TIME to (emptyList<Frame>() to timeSet),
        Opcode.FLUSH to (emptyList<Frame>() to flushed),
        Opcode.STATUS to (emptyList<Frame>() to null),
        Opcode.GET_CONFIG to (emptyList<Frame>() to config),
        Opcode.SET_CONFIG to (emptyList<Frame>() to config),
        Opcode.WIFI_SCAN to (listOf<Frame>(wifiAp) to scanEnd),
        Opcode.REBOOT to (emptyList<Frame>() to rebooting),
        Opcode.LOG_TAIL to (listOf<Frame>(logLine) to logEnd),
        Opcode.GET_TOKEN to (emptyList<Frame>() to token),
        Opcode.GET_DIAG to (emptyList<Frame>() to diag),
        Opcode.ABORT to (emptyList<Frame>() to aborted),
    )

    @Test
    fun tableCoversEveryOpcodeAndEveryFrame() {
        assertEquals((Opcode.LIST..Opcode.ABORT).toSet(), table.keys)
        for ((op, rule) in table) {
            val (continues, terminal) = rule
            for (frame in all) {
                val expected = when {
                    frame === terminal -> TERMINAL
                    frame in continues -> CONTINUE
                    else -> FOREIGN
                }
                assertEquals(expected, TerminalRules.classify(op, frame), "${Opcode.name(op)} on $frame")
            }
        }
    }

    @Test
    fun errorEndsTheReplyWhenItsOpMatchesOrIsZero() {
        for (op in table.keys) {
            assertEquals(TERMINAL, TerminalRules.classify(op, Frame.Error(op, 7, "")))
            assertEquals(FOREIGN, TerminalRules.classify(op, Frame.Error(op, 16, "")), "ERROR 16 only answers ABORT")
            assertEquals(TERMINAL, TerminalRules.classify(op, Frame.Error(0, 1, "")))
            val other = if (op == Opcode.LIST) Opcode.CLOSE else Opcode.LIST
            assertEquals(FOREIGN, TerminalRules.classify(op, Frame.Error(other, 7, "")))
        }
    }

    @Test
    fun configChunkEndsOnTheFinalSlice() {
        for (op in listOf(Opcode.GET_CONFIG, Opcode.SET_CONFIG)) {
            assertEquals(CONTINUE, TerminalRules.classify(op, Frame.ConfigChunk(0, 10, 0, ByteArray(6))))
            assertEquals(TERMINAL, TerminalRules.classify(op, Frame.ConfigChunk(0, 10, 6, ByteArray(4))))
            assertEquals(FOREIGN, TerminalRules.classify(Opcode.LIST, Frame.ConfigChunk(0, 10, 6, ByteArray(4))))
        }
    }

    @Test
    fun malformedConfigChunkEndsTheReplySoTheOwnerFailsAtOnce() {
        val malformed = listOf(
            Frame.ConfigChunk(0, 10, 8, ByteArray(4)),
            Frame.ConfigChunk(0, 10, 0, ByteArray(0)),
            Frame.ConfigChunk(0, 1, 0, ByteArray(1)),
            Frame.ConfigChunk(0, 481, 0, ByteArray(100)),
        )
        for (chunk in malformed) assertEquals(TERMINAL, TerminalRules.classify(Opcode.GET_CONFIG, chunk), "$chunk")
    }
}

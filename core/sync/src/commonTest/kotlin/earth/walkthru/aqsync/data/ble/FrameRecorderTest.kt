package earth.walkthru.aqsync.data.ble

import earth.walkthru.aqsync.data.protocol.FrameType
import earth.walkthru.aqsync.data.protocol.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameRecorderTest {
    @Test
    fun chunkSnapshotsAreBoundedButTerminalFramesPublishCompleteRingHistory() {
        var now = 0L
        val recorder = FrameRecorder(capacity = 4, monotonicMs = { now })
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 1))
        val first = recorder.records.value
        now = 1
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 2))
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 3))
        assertSame(first, recorder.records.value)
        now = 100
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 4))
        assertEquals(listOf("2101", "2102", "2103", "2104"), recorder.records.value.map { it.previewHex })
        now = 101
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 5))
        recorder.rx(byteArrayOf(FrameType.READ_END.toByte()))
        assertEquals(listOf("2103", "2104", "2105", "22"), recorder.records.value.map { it.previewHex })
    }

    @Test
    fun secretsStayRedactedAndPreviewsKeepExactByteValuesAndLengths() {
        val recorder = FrameRecorder()
        recorder.rx(byteArrayOf(FrameType.TOKEN.toByte(), 0x12, 0x34))
        recorder.tx(byteArrayOf(Opcode.SET_CONFIG.toByte(), 0x56, 0x78))
        recorder.rx(ByteArray(32) { if (it == 0) FrameType.CHUNK.toByte() else (0xF0 + it).toByte() })
        recorder.rx(byteArrayOf(FrameType.READ_END.toByte()))
        val records = recorder.records.value
        assertEquals("46…", records[0].previewHex)
        assertEquals("09…", records[1].previewHex)
        assertTrue(records[0].redacted)
        assertTrue(records[1].redacted)
        assertEquals(3, records[0].length)
        assertEquals("21f1f2f3f4f5f6f7f8f9fafbfcfdfeff", records[2].previewHex)
        assertEquals(32, records[2].length)
        assertFalse(records[2].redacted)
    }

    @Test
    fun failedRequestFlushExposesItsFinalChunksWithoutWaitingForATerminalFrame() {
        val recorder = FrameRecorder(monotonicMs = { 0 })
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 1))
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 2))
        assertEquals(1, recorder.records.value.size)
        recorder.flush()
        val flushed = recorder.records.value
        assertEquals(listOf("2101", "2102"), flushed.map { it.previewHex })
        recorder.flush()
        assertSame(flushed, recorder.records.value)
    }

    @Test
    fun clearingResetsBothRingAndSnapshotCadence() {
        val recorder = FrameRecorder(monotonicMs = { 0 })
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 1))
        recorder.clear()
        assertTrue(recorder.records.value.isEmpty())
        recorder.rx(byteArrayOf(FrameType.CHUNK.toByte(), 2))
        assertEquals("2102", recorder.records.value.single().previewHex)
    }
}

package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class ConfigChunkAccumulatorTest {
    @Test fun `reassembles utf8 split across BLE notifications`() {
        val json = """{"wifi":{"ssid":"Café"},"sensor":{"serial":"PMS5003T-202604081332"}}"""
        val bytes = json.encodeToByteArray()
        val cut = bytes.indexOf(0xC3.toByte()) + 1 // split inside é
        val first = FrameCodec.decode(FrameCodec.encode(Frame.ConfigChunk(1, bytes.size, 0, bytes.copyOfRange(0, cut)))) as Frame.ConfigChunk
        val second = FrameCodec.decode(FrameCodec.encode(Frame.ConfigChunk(1, bytes.size, cut, bytes.copyOfRange(cut, bytes.size)))) as Frame.ConfigChunk
        val assembler = ConfigChunkAccumulator()
        assertNull(assembler.add(first))
        val complete = assembler.add(second)!!
        assertEquals(1, complete.flags)
        assertEquals(json, complete.json)
    }

    @Test fun `rejects missing or inconsistent chunks`() {
        val assembler = ConfigChunkAccumulator()
        assertNull(assembler.add(Frame.ConfigChunk(0, 4, 0, byteArrayOf(1, 2))))
        for (bad in listOf(
            Frame.ConfigChunk(0, 4, 3, byteArrayOf(3)),
            Frame.ConfigChunk(1, 4, 2, byteArrayOf(3)),
            Frame.ConfigChunk(0, 5, 2, byteArrayOf(3)),
        )) {
            try {
                assembler.add(bad)
                fail("accepted malformed CONFIG_CHUNK")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

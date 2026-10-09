package earth.walkthru.aqsync.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal val clockTestAnchor = ClockAnchor("station-a", "0123456789abcdef0123456789abcdef", 1_790_000_000_123_456_000L, 123_456L)

class ClockAnchorReceiptTest {
    @Test
    fun validatesClockEvidenceAndPathComponents() {
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(station = "../elsewhere") }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(boot = "boot-1") }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(boot = "F".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(utcNs = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(monoUs = -1) }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(monoUs = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { clockTestAnchor.copy(source = "../phone") }
        assertEquals("3", clockTestAnchor.copy(source = "3").source)
    }

    @Test
    fun rejectsMalformedAndMisplacedReceipts() {
        val anchor = clockTestAnchor
        val path = ClockAnchorReceipt.relativePath(anchor)
        val encoded = ClockAnchorReceipt.encode(anchor)
        assertEquals(anchor, ClockAnchorReceipt.decode(encoded, anchor.station, path))
        assertNull(ClockAnchorReceipt.decode(encoded.copyOf(encoded.size - 1), anchor.station, path))
        assertNull(ClockAnchorReceipt.decode(encoded, "another-station", path))
        assertNull(ClockAnchorReceipt.decode(encoded, anchor.station, "0".repeat(32) + "/" + path.substringAfter('/')))
    }
}

/** Restart and boot isolation are exercised against both real platform file implementations. */
abstract class ClockAnchorStoreContract {
    abstract fun newStore(): ClockAnchorStore
    abstract fun writeMalformedReceipt()

    @Test
    fun receiptsSurviveRestartAndEarliestWinsWithoutLosingOtherBoots() = runTest {
        val early = clockTestAnchor
        val later = early.copy(monoUs = early.monoUs + 1_000_000L, utcNs = early.utcNs + 1_000_000_000L)
        val secondBoot = early.copy(boot = "f".repeat(32))
        val otherStation = early.copy(station = "station-b")
        newStore().apply {
            put(later)
            put(early)
            put(early)
            put(secondBoot)
            put(otherStation)
        }
        writeMalformedReceipt()
        assertEquals(listOf(early, secondBoot), newStore().anchors(early.station))
        assertEquals(listOf(otherStation), newStore().anchors(otherStation.station))
        assertEquals(emptyList(), newStore().anchors("unknown"))
        assertFailsWith<IllegalArgumentException> { newStore().anchors("../station-a") }
    }
}

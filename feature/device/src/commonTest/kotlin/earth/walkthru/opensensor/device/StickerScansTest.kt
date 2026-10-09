package earth.walkthru.opensensor.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StickerScansTest {
    private val unrecognized = "not a sticker"

    @Test
    fun aScannedStickerFillsTheFieldThroughTheSharedParser() {
        val scan = StickerScans.read(null, "  PMS5003T-202604081332\n", unrecognized)
        assertEquals(StickerScan("PMS5003T-202604081332", null, 1), scan)
    }

    @Test
    fun cancelLeavesTheStateUnchanged() {
        assertNull(StickerScans.read(null, null, unrecognized))
        val earlier = StickerScan("PMS5003T-202604081332", null, 4)
        assertSame(earlier, StickerScans.read(earlier, null, unrecognized))
        val failed = StickerScan(null, "camera off", 5)
        assertSame(failed, StickerScans.read(failed, null, unrecognized))
    }

    @Test
    fun aBarcodeTheParserDoesNotKnowIsAnErrorWithNoValue() {
        assertEquals(StickerScan(null, unrecognized, 1), StickerScans.read(null, "https://example.com/box", unrecognized))
        assertEquals(StickerScan(null, unrecognized, 1), StickerScans.read(null, "", unrecognized))
        // A date that is not a real day fails the parser too.
        assertEquals(StickerScan(null, unrecognized, 1), StickerScans.read(null, "PMS5003T-202602301332", unrecognized))
    }

    @Test
    fun anotherPlantowerModelReachesTheFieldAndTheModelCheckDecides() {
        // The parser knows PMS5003, the card and saveSensor then refuse it as not the fitted model.
        val scan = StickerScans.read(null, "PMS5003-202604081332", unrecognized)
        assertEquals("PMS5003-202604081332", scan?.value)
    }

    @Test
    fun theSameStickerScannedTwiceIsANewResult() {
        val first = StickerScans.read(null, "PMS5003T-202604081332", unrecognized)
        val second = StickerScans.read(first, "PMS5003T-202604081332", unrecognized)
        assertEquals(first?.value, second?.value)
        assertEquals(2, second?.id)
    }

    @Test
    fun aFailureKeepsManualEntryAndTakesTheNextId() {
        val earlier = StickerScan("PMS5003T-202604081332", null, 7)
        assertEquals(StickerScan(null, "camera off", 8), StickerScans.failed(earlier, "camera off"))
        assertEquals(StickerScan(null, "camera off", 1), StickerScans.failed(null, "camera off"))
    }
}

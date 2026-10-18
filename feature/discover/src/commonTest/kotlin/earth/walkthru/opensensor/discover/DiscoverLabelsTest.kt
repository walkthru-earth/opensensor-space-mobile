package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.Pm25Basis
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.opensensor.designsystem.pm25LabelRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #92. The public feed has no humidity, so its PM2.5 is always labelled raw and nothing else is relabelled. */
class DiscoverLabelsTest {
    private val pm25 = FeedProperty("pm25", "PM2.5", "µg/m³", "Air")
    private val pm10 = FeedProperty("pm10", "PM10", "µg/m³", "Air")

    @Test
    fun theFeedPm25IsLabelledRaw() {
        assertTrue(isRawPm25(pm25))
        assertEquals(pm25LabelRes(Pm25Basis.RAW), shownLabelRes(pm25))
    }

    @Test
    fun otherFeedPropertiesKeepTheFeedLabel() {
        assertFalse(isRawPm25(pm10))
        assertNull(shownLabelRes(pm10))
        assertNull(shownLabelRes(FeedProperty("pm1", "PM1", "µg/m³", "Air")))
    }
}

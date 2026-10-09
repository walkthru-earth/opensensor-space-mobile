package earth.walkthru.aqsync.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SensorLabelTest {
    @Test
    fun extractsModelAndBatchFromExactPlantowerSticker() {
        assertEquals(
            SensorLabel("Plantower", "PMS5003T", "PMS5003T-202604081332", "PMS5003T-20260408"),
            SensorLabel.parse(" pms5003t-202604081332 "),
        )
    }

    @Test
    fun rejectsUnrelatedCodeAndImpossibleBatchDate() {
        assertNull(SensorLabel.parse("202604081332"))
        assertNull(SensorLabel.parse("PMS5003T-202613011332"))
        assertNull(SensorLabel.parse("PMS5003T-20260408"))
        assertNull(SensorLabel.parse("PMS5003T-202602301332"))
        assertNull(SensorLabel.parse("PMS5003T-202604001332"))
        assertNull(SensorLabel.parse("PMS5003T-199912311332"))
    }

    @Test
    fun acceptsLeapDayOnlyInLeapYears() {
        assertNotNull(SensorLabel.parse("PMS5003T-202402291"))
        assertNotNull(SensorLabel.parse("PMS5003T-200002291"))
        assertNull(SensorLabel.parse("PMS5003T-202502291"))
        assertNull(SensorLabel.parse("PMS5003T-210002291"))
    }

    @Test
    fun acceptsLongerUnitSuffixWithoutChangingBatch() {
        assertEquals("PMS5003T-20260408", SensorLabel.parse("PMS5003T-2026040812345")?.batch)
    }
}

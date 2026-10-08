package earth.walkthru.aqsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorLabelTest {
    @Test fun `extracts model and batch from exact Plantower sticker`() {
        assertEquals(
            SensorLabel("Plantower", "PMS5003T", "PMS5003T-202604081332", "PMS5003T-20260408"),
            SensorLabel.parse(" pms5003t-202604081332 "),
        )
    }

    @Test fun `rejects unrelated code and impossible batch date`() {
        assertNull(SensorLabel.parse("202604081332"))
        assertNull(SensorLabel.parse("PMS5003T-202613011332"))
        assertNull(SensorLabel.parse("PMS5003T-20260408"))
    }

    @Test fun `accepts longer unit suffix without changing batch`() {
        assertEquals("PMS5003T-20260408", SensorLabel.parse("PMS5003T-2026040812345")?.batch)
    }
}

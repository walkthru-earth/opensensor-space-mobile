package earth.walkthru.aqsync.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlantowerBatchCandidateTest {
    @Test fun `count formula uses exact batch and valid frame`() {
        assertEquals(3.964, PlantowerBatchCandidate.estimate("PMS5003T-202604081332", 1, 4, 1000)!!, 1e-9)
        assertNull(PlantowerBatchCandidate.estimate("PMS5003T-202604091332", 1, 4, 1000))
        assertNull(PlantowerBatchCandidate.estimate("PMS5003T-202604081332", 0, 4, 1000))
        assertNull(PlantowerBatchCandidate.estimate("PMS5003T-202604081332", 1, 2, 1000))
        assertNull(PlantowerBatchCandidate.estimate("PMS5003T-202604081332", 1, 4, null))
    }
}

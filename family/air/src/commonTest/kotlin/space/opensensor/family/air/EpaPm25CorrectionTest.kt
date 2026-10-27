package space.opensensor.family.air

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpaPm25CorrectionTest {
    private fun assertCorrected(expected: Double, cf1: Double, rh: Double) {
        val got = EpaPm25Correction.correct(cf1, rh)
        assertTrue(got != null && abs(got - expected) < 1e-4, "cf1=$cf1 rh=$rh expected $expected got $got")
    }

    /**
     * Reference vectors worked by hand from the published piecewise equation (EPA Fire and Smoke Map,
     * Barkjohn et al. 2021 and 2022) across 0 to 300 µg/m³ and 0 to 100 percent RH, one row per
     * segment plus both blend zones at their midpoints.
     */
    @Test
    fun matchesPublishedEquationAcrossTheRange() {
        val vectors = listOf(
            // cf1, rh, expected
            Triple(0.0, 0.0, 5.75),
            Triple(0.0, 50.0, 1.44),
            Triple(10.0, 0.0, 10.99),
            Triple(10.0, 50.0, 6.68),
            Triple(10.0, 100.0, 2.37),
            Triple(40.0, 0.0, 31.95), // blend zone 30 to 50, t = 0.5
            Triple(40.0, 50.0, 27.64),
            Triple(40.0, 100.0, 23.33),
            Triple(100.0, 0.0, 84.35),
            Triple(100.0, 50.0, 80.04),
            Triple(100.0, 100.0, 75.73),
            Triple(205.0, 50.0, 162.57), // just below the 210 breakpoint, still the middle segment
            Triple(235.0, 0.0, 202.1975), // blend zone 210 to 260, t = 0.5
            Triple(235.0, 50.0, 200.04245),
            Triple(235.0, 100.0, 197.8875),
            Triple(300.0, 0.0, 289.526), // no RH term above 260
            Triple(300.0, 100.0, 289.526),
        )
        for ((cf1, rh, expected) in vectors) assertCorrected(expected, cf1, rh)
    }

    @Test
    fun breakpointsTakeTheUpperSegment() {
        assertCorrected(21.47, 30.0, 0.0)
        assertCorrected(45.05, 50.0, 0.0)
        assertCorrected(170.81, 210.0, 0.0)
        assertCorrected(242.1244, 260.0, 50.0)
    }

    @Test
    fun segmentsMeetAtEveryBreakpoint() {
        for (bp in listOf(30.0, 50.0, 210.0, 260.0)) {
            for (rh in listOf(0.0, 35.0, 100.0)) {
                val below = EpaPm25Correction.correct(bp - 1e-9, rh)!!
                val at = EpaPm25Correction.correct(bp, rh)!!
                assertTrue(abs(below - at) < 1e-3, "jump at $bp rh=$rh, $below vs $at")
            }
        }
    }

    @Test
    fun negativeResultClampsToZero() {
        // 0.524 * 0 - 0.0862 * 100 + 5.75 = -2.87 before the clamp
        assertEquals(0.0, EpaPm25Correction.correct(0.0, 100.0))
        assertEquals(0.0, EpaPm25Correction.correct(1.0, 90.0))
    }

    @Test
    fun rhOutsideZeroToHundredGivesNoValue() {
        assertNull(EpaPm25Correction.correct(10.0, null))
        assertNull(EpaPm25Correction.correct(10.0, -0.1))
        assertNull(EpaPm25Correction.correct(10.0, 100.1))
        assertNull(EpaPm25Correction.correct(10.0, Double.NaN))
        assertNull(EpaPm25Correction.correct(10.0, Double.POSITIVE_INFINITY))
        assertTrue(EpaPm25Correction.isValidRh(0.0))
        assertTrue(EpaPm25Correction.isValidRh(100.0))
        assertFalse(EpaPm25Correction.isValidRh(101.0))
    }

    @Test
    fun invalidPmGivesNoValue() {
        assertNull(EpaPm25Correction.correct(null, 50.0))
        assertNull(EpaPm25Correction.correct(-1.0, 50.0))
        assertNull(EpaPm25Correction.correct(Double.NaN, 50.0))
        assertNull(EpaPm25Correction.correct(Double.POSITIVE_INFINITY, 50.0))
    }
}

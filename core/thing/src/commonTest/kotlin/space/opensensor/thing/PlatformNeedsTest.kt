package space.opensensor.thing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformNeedsTest {
    @Test
    fun everyKindOfTheAppHasARow() {
        assertEquals(PlatformNeedsTable.APP + PlatformNeedsTable.OPENSENSOR_BOARD, PlatformNeedsTable.of(listOf(OpenSensorBoardKind)))
        assertEquals(PlatformNeedsTable.APP, PlatformNeedsTable.of(emptyList()))
    }

    @Test
    fun aKindWithoutARowFails() {
        assertFailsWith<IllegalArgumentException> { PlatformNeedsTable.of(listOf(localOnlyKind)) }
    }

    @Test
    fun reasonsOfTheSameCategoryMerge() {
        val a = IosNeeds(accessedApis = mapOf("C" to setOf("A.1")))
        val b = IosNeeds(accessedApis = mapOf("C" to setOf("B.1"), "D" to setOf("D.1")))
        assertEquals(mapOf("C" to setOf("A.1", "B.1"), "D" to setOf("D.1")), (a + b).accessedApis)
    }

    @Test
    fun usageStringsAndFlagsAreGated() {
        assertTrue(IosNeeds.isGatedInfoKey("NSMotionUsageDescription"))
        assertTrue(IosNeeds.isGatedInfoKey("NSLocationTemporaryUsageDescriptionDictionary"))
        assertTrue(IosNeeds.isGatedInfoKey("NSLocationDefaultAccuracyReduced"))
        assertFalse(IosNeeds.isGatedInfoKey("CFBundleDisplayName"))
    }

    @Test
    fun noKindAsksForFineOrBackgroundLocation() {
        val all = PlatformNeedsTable.of(listOf(OpenSensorBoardKind))
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION"), all.android.permissions.filter { "LOCATION" in it }.toSet())
        assertFalse("location" in all.ios.backgroundModes)
        assertTrue(all.ios.collectedDataTypes.isEmpty())
    }
}

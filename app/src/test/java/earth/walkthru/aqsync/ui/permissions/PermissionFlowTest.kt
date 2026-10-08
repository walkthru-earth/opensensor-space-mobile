package earth.walkthru.aqsync.ui.permissions

import earth.walkthru.aqsync.R
import earth.walkthru.opensensor.designsystem.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Permission timing: nothing at launch, ask from the tap, explain only when due, settings last. */
class PermissionFlowTest {

    @Test
    fun tapDecides() {
        assertEquals(AccessStep.READY, PermissionFlow.onTap(granted = true, showRationale = false, radioOn = true))
        assertEquals(AccessStep.TURN_ON, PermissionFlow.onTap(granted = true, showRationale = false, radioOn = false))
        assertEquals(AccessStep.ASK, PermissionFlow.onTap(granted = false, showRationale = false))
        assertEquals(AccessStep.EXPLAIN_THEN_ASK, PermissionFlow.onTap(granted = false, showRationale = true))
    }

    @Test
    fun resultDecides() {
        assertEquals(AccessStep.READY, PermissionFlow.onResult(granted = true, showRationale = false))
        assertEquals(AccessStep.TURN_ON, PermissionFlow.onResult(granted = true, showRationale = false, radioOn = false))
        assertEquals(AccessStep.DECLINED, PermissionFlow.onResult(granted = false, showRationale = true))
        assertEquals(AccessStep.OPEN_SETTINGS, PermissionFlow.onResult(granted = false, showRationale = false))
    }

    private class Fake {
        var granted = false
        var rationale = false
        var radio = true
        var asked = 0
        var turnOn = 0
        var settings = 0
        val access = RuntimeAccess(AccessTexts(R.string.perm_bt_title, R.string.perm_bt_body, R.string.perm_bt_settings_title, R.string.perm_bt_settings_body, AppIcons.Bluetooth)).also {
            it.check = { granted }
            it.rationaleDue = { rationale }
            it.radioCheck = { radio }
            it.launchPermission = { asked++ }
            it.launchTurnOn = { turnOn++ }
            it.launchSettings = { settings++ }
        }
    }

    @Test
    fun grantedActionRunsWithoutAnyDialog() {
        val f = Fake().apply { granted = true }
        var ran = false
        f.access.run { ran = true }
        assertTrue(ran)
        assertEquals(0, f.asked)
        assertNull(f.access.dialog)
    }

    @Test
    fun firstTapAsksThenRunsAfterGrant() {
        val f = Fake()
        var ran = false
        f.access.run { ran = true }
        assertEquals(1, f.asked)
        assertFalse("nothing runs before the answer", ran)
        f.granted = true
        f.access.onPermissionResult()
        assertTrue(ran)
        assertTrue(f.access.ready)
    }

    @Test
    fun rationaleIsShownOnlyWhenDueAndAsksOnContinue() {
        val f = Fake().apply { rationale = true }
        var declined = false
        f.access.run(onDeclined = { declined = true }) { }
        assertEquals(AccessDialog.RATIONALE, f.access.dialog)
        assertEquals(0, f.asked)
        f.access.confirmRationale()
        assertEquals(1, f.asked)
        assertNull(f.access.dialog)
        // Denied again, still due: no dialog, the caller's fallback runs.
        f.access.onPermissionResult()
        assertTrue(declined)
        assertNull(f.access.dialog)
    }

    @Test
    fun permanentDenialOffersSettings() {
        val f = Fake()
        var declined = false
        f.access.run(onDeclined = { declined = true }) { }
        f.access.onPermissionResult() // denied, no rationale due
        assertEquals(AccessDialog.SETTINGS, f.access.dialog)
        f.access.openSettings()
        assertEquals(1, f.settings)
        assertTrue(declined)
    }

    @Test
    fun radioOffAsksToTurnOnThenRuns() {
        val f = Fake().apply { granted = true; radio = false }
        var ran = false
        f.access.run { ran = true }
        assertEquals(1, f.turnOn)
        assertFalse(ran)
        f.radio = true
        f.access.onTurnOnResult()
        assertTrue(ran)
    }
}

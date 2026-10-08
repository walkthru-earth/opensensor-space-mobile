package earth.walkthru.opensensor.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Permission timing. Nothing at launch, ask from the tap, explain only when due, settings last. */
class PermissionFlowTest {

    // Android, the grant and the rationale flag.

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

    // iOS, the reported status.

    @Test
    fun iosTapDecides() {
        assertEquals(AccessStep.READY, PermissionFlow.onTap(AccessStatus.GRANTED))
        assertEquals(AccessStep.TURN_ON, PermissionFlow.onTap(AccessStatus.GRANTED, radioOn = false))
        assertEquals(AccessStep.ASK, PermissionFlow.onTap(AccessStatus.NOT_DETERMINED))
        assertEquals(AccessStep.OPEN_SETTINGS, PermissionFlow.onTap(AccessStatus.DENIED))
        assertEquals(AccessStep.DECLINED, PermissionFlow.onTap(AccessStatus.RESTRICTED))
        // Local network has no status. The action itself shows the prompt.
        assertEquals(AccessStep.READY, PermissionFlow.onTap(AccessStatus.UNKNOWN))
    }

    @Test
    fun iosResultRespectsAFreshNo() {
        assertEquals(AccessStep.READY, PermissionFlow.onResult(AccessStatus.GRANTED))
        assertEquals(AccessStep.DECLINED, PermissionFlow.onResult(AccessStatus.DENIED))
        assertEquals(AccessStep.DECLINED, PermissionFlow.onResult(AccessStatus.RESTRICTED))
        assertEquals(AccessStep.DECLINED, PermissionFlow.onResult(AccessStatus.NOT_DETERMINED))
        assertEquals(AccessStep.READY, PermissionFlow.onResult(AccessStatus.UNKNOWN))
    }

    @Test
    fun iosNeverExplainsFirst() {
        AccessStatus.entries.forEach { status ->
            assertTrue(PermissionFlow.onTap(status) != AccessStep.EXPLAIN_THEN_ASK)
            assertTrue(PermissionFlow.onResult(status) != AccessStep.EXPLAIN_THEN_ASK)
        }
    }

    @Test
    fun settingsLinkOnlyForDenied() {
        assertEquals(setOf(AccessStatus.DENIED), AccessStatus.entries.filter(PermissionFlow::offersSettings).toSet())
    }

    @Test
    fun iosBluetoothMapping() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.bluetooth(0))
        assertEquals(AccessStatus.RESTRICTED, IosAuthorization.bluetooth(1))
        assertEquals(AccessStatus.DENIED, IosAuthorization.bluetooth(2))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.bluetooth(3))
        assertEquals(AccessStatus.UNKNOWN, IosAuthorization.bluetooth(9))
    }

    @Test
    fun iosLocationMapping() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.location(0))
        assertEquals(AccessStatus.RESTRICTED, IosAuthorization.location(1))
        assertEquals(AccessStatus.DENIED, IosAuthorization.location(2))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.location(3))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.location(4))
    }

    @Test
    fun iosNotificationMapping() {
        assertEquals(AccessStatus.NOT_DETERMINED, IosAuthorization.notifications(0))
        assertEquals(AccessStatus.DENIED, IosAuthorization.notifications(1))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(2))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(3))
        assertEquals(AccessStatus.GRANTED, IosAuthorization.notifications(4))
    }

    @Test
    fun iosLocalNetworkIsUnknownUntilPolicyDenied() {
        assertEquals(AccessStatus.UNKNOWN, IosAuthorization.localNetwork(policyDenied = false))
        assertEquals(AccessStatus.DENIED, IosAuthorization.localNetwork(policyDenied = true))
        assertTrue(PermissionFlow.offersSettings(IosAuthorization.localNetwork(policyDenied = true)))
    }

    // The trigger table.

    @Test
    fun nothingIsAskedAtLaunch() {
        assertTrue(PermissionTriggers.of(UserAction.APP_LAUNCH).all.isEmpty())
        AccessKind.entries.forEach { assertFalse(UserAction.APP_LAUNCH in PermissionTriggers.actionsAsking(it)) }
    }

    @Test
    fun everyActionIsInTheTable() {
        assertEquals(UserAction.entries.toSet(), PermissionTriggers.table.keys)
    }

    @Test
    fun eachPermissionComesFromItsOwnTaps() {
        assertEquals(setOf(UserAction.FIND_SENSORS, UserAction.OPEN_SENSOR, UserAction.RECONNECT), PermissionTriggers.actionsAsking(AccessKind.BLUETOOTH))
        assertEquals(setOf(UserAction.WIFI_ACTION, UserAction.SAVE_WIFI_ACCESS), PermissionTriggers.actionsAsking(AccessKind.LOCAL_NETWORK))
        assertEquals(setOf(UserAction.NEAR_ME), PermissionTriggers.actionsAsking(AccessKind.APPROX_LOCATION))
        assertEquals(setOf(UserAction.SYNC, UserAction.AUTO_SYNC_ON), PermissionTriggers.actionsAsking(AccessKind.NOTIFICATIONS))
    }

    @Test
    fun notificationsNeverBlockAnAction() {
        PermissionTriggers.table.values.forEach { assertFalse(AccessKind.NOTIFICATIONS in it.required) }
    }

    // RuntimeAccess, with a fake platform.

    private class Fake(val ios: Boolean = false) {
        var granted = false
        var rationale = false
        var status = AccessStatus.NOT_DETERMINED
        var radio = true
        var asked = 0
        var turnOn = 0
        var settings = 0
        val access = RuntimeAccess(AccessKind.BLUETOOTH).also {
            it.bind(
                if (ios) {
                    AccessHooks.statusBased(
                        status = { status },
                        requestPermission = { asked++ },
                        openSettings = { settings++ },
                        radioOn = { radio },
                        requestTurnOn = { turnOn++ },
                    )
                } else {
                    AccessHooks.rationaleBased(
                        granted = { granted },
                        rationaleDue = { rationale },
                        requestPermission = { asked++ },
                        openSettings = { settings++ },
                        radioOn = { radio },
                        requestTurnOn = { turnOn++ },
                    )
                },
            )
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
        assertFalse(ran, "nothing runs before the answer")
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
        // Denied again, still due. No dialog, the caller's fallback runs.
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

    @Test
    fun iosDenialIsQuietThenTheNextTapOffersSettings() {
        val f = Fake(ios = true)
        var declined = 0
        var ran = false
        f.access.run(onDeclined = { declined++ }) { ran = true }
        assertEquals(1, f.asked)
        f.status = AccessStatus.DENIED
        f.access.onPermissionResult()
        assertEquals(1, declined)
        assertNull(f.access.dialog, "a fresh no gets no settings dialog")
        f.access.run(onDeclined = { declined++ }) { ran = true }
        assertEquals(1, f.asked, "iOS shows its prompt once")
        assertEquals(AccessDialog.SETTINGS, f.access.dialog)
        f.access.openSettings()
        assertEquals(1, f.settings)
        assertEquals(2, declined)
        assertFalse(ran)
    }

    @Test
    fun iosGrantRuns() {
        val f = Fake(ios = true)
        var ran = false
        f.access.run { ran = true }
        f.status = AccessStatus.GRANTED
        f.access.onPermissionResult()
        assertTrue(ran)
        assertTrue(f.access.ready)
    }

    @Test
    fun iosRestrictedNeverAsksNorLinks() {
        val f = Fake(ios = true).apply { status = AccessStatus.RESTRICTED }
        var declined = false
        f.access.run(onDeclined = { declined = true }) { }
        assertTrue(declined)
        assertEquals(0, f.asked)
        assertNull(f.access.dialog)
    }

    @Test
    fun dismissDeclines() {
        val f = Fake(ios = true).apply { status = AccessStatus.DENIED }
        var declined = false
        f.access.run(onDeclined = { declined = true }) { }
        f.access.dismiss()
        assertTrue(declined)
        assertEquals(0, f.settings)
        assertNull(f.access.dialog)
    }
}

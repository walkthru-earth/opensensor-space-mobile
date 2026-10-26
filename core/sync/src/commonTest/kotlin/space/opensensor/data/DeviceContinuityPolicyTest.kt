package space.opensensor.data

import space.opensensor.data.DeviceContinuityPolicy.Decision
import space.opensensor.data.DeviceContinuityPolicy.Identity
import space.opensensor.data.DeviceContinuityPolicy.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceContinuityPolicyTest {
    private val known = Identity("AA:BB:CC:DD:EE:01", "441bf6e26b40", "thing-old")

    @Test
    fun thingRolloverCannotEscapeItsArchiveNamespace() {
        for (thing in listOf(".", "..", "../outside", "thing/child")) {
            assertEquals(Decision.Reject(Reason.THING_MALFORMED), decide(known.copy(thing = thing), ble = true))
        }
    }

    @Test
    fun sameEnrolledDeviceMayReconnectOverEitherTransport() {
        assertEquals(Decision.Accept(), decide(known, ble = true))
        assertEquals(Decision.Accept(), decide(known, ble = false))
    }

    @Test
    fun newThingOnTheAuthenticatedSameHardwareBecomesAnEpochAndInvalidatesCredentials() {
        assertEquals(Decision.Accept(thingChanged = true, invalidateLanToken = true), decide(known.copy(thing = "thing-new"), ble = true))
    }

    @Test
    fun lanCannotAuthorizeAThingGenerationChange() {
        assertEquals(Decision.Reject(Reason.THING_CHANGE_REQUIRES_BLE), decide(known.copy(thing = "thing-new"), ble = false))
    }

    @Test
    fun sameThingCannotDisguiseChangedHardwareOnBleOrLan() {
        for (ble in listOf(true, false)) {
            assertEquals(Decision.Reject(Reason.HARDWARE_CHANGED), decide(known.copy(deviceId = "a4cb8fd77500"), ble))
        }
    }

    @Test
    fun aChangedEndpointCannotBeSilentlyReassignedEvenWithTheSameReportedHardware() {
        assertEquals(Decision.Reject(Reason.ENDPOINT_CHANGED), decide(known.copy(address = "AA:BB:CC:DD:EE:02"), ble = true))
    }

    @Test
    fun missingPinnedHardwareIdentityRejectsAReconnect() {
        assertEquals(Decision.Reject(Reason.HARDWARE_MISSING), decide(known.copy(deviceId = null), ble = true))
    }

    @Test
    fun malformedHardwareIdentityRejectsAReconnect() {
        for (id in listOf("", "6b40", "441bf6e26b4z", "441bf6e26b400")) {
            assertEquals(Decision.Reject(Reason.HARDWARE_MALFORMED), decide(known.copy(deviceId = id), ble = true))
        }
    }

    @Test
    fun missingThingCannotBeUsedToCollectFiles() {
        for (thing in listOf(null, "", " ")) {
            assertEquals(Decision.Reject(Reason.THING_MISSING), decide(known.copy(thing = thing), ble = true))
        }
    }

    @Test
    fun addressAndFullHardwareHexComparisonsIgnoreCase() {
        assertEquals(Decision.Accept(), decide(known.copy(address = known.address.lowercase(), deviceId = known.deviceId!!.uppercase()), ble = false))
    }

    @Test
    fun firstEnrollmentRequiresOwnerApprovedBleButOptionalDevRemainsCompatible() {
        for (deviceId in listOf(null, known.deviceId)) {
            val info = known.copy(deviceId = deviceId)
            assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(null, info, authenticatedBle = true))
            assertEquals(Decision.Reject(Reason.ENROLLMENT_REQUIRES_BLE), DeviceContinuityPolicy.evaluate(null, info, authenticatedBle = false))
        }
    }

    @Test
    fun anEmptySavedScanRowIsStillAFirstEnrollment() {
        val scanned = known.copy(deviceId = null, thing = null)
        assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(scanned, known, authenticatedBle = true))
        assertEquals(Decision.Reject(Reason.ENROLLMENT_REQUIRES_BLE), DeviceContinuityPolicy.evaluate(scanned, known, authenticatedBle = false))
    }

    @Test
    fun legacyDevicesWithoutDevMayCollectSameThingButCannotAutomaticallyLinkANewOne() {
        val legacy = known.copy(deviceId = null)
        assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(legacy, legacy, authenticatedBle = false))
        assertEquals(Decision.Reject(Reason.THING_CHANGE_REQUIRES_HARDWARE), DeviceContinuityPolicy.evaluate(legacy, legacy.copy(thing = "thing-new"), authenticatedBle = true))
        assertEquals(Decision.Reject(Reason.THING_CHANGE_REQUIRES_HARDWARE), DeviceContinuityPolicy.evaluate(legacy, known.copy(thing = "thing-new"), authenticatedBle = true))
    }

    private fun decide(observed: Identity, ble: Boolean): Decision = DeviceContinuityPolicy.evaluate(known, observed, authenticatedBle = ble)
}

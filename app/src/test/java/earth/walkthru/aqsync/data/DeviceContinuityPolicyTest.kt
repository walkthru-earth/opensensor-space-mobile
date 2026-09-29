package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.DeviceContinuityPolicy.Decision
import earth.walkthru.aqsync.data.DeviceContinuityPolicy.Identity
import earth.walkthru.aqsync.data.DeviceContinuityPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceContinuityPolicyTest {
    private val known = Identity("AA:BB:CC:DD:EE:01", "441bf6e26b40", "station-old")

    @Test
    fun `station rollover cannot escape its archive namespace`() {
        for (station in listOf(".", "..", "../outside", "station/child")) {
            assertEquals(Decision.Reject(Reason.STATION_MALFORMED), decide(known.copy(station = station), ble = true))
        }
    }

    @Test
    fun `same enrolled device may reconnect over either transport`() {
        assertEquals(Decision.Accept(), decide(known, ble = true))
        assertEquals(Decision.Accept(), decide(known, ble = false))
    }

    @Test
    fun `new station on the authenticated same hardware becomes an epoch and invalidates credentials`() {
        assertEquals(Decision.Accept(stationChanged = true, invalidateLanToken = true), decide(known.copy(station = "station-new"), ble = true))
    }

    @Test
    fun `LAN cannot authorize a station generation change`() {
        assertEquals(Decision.Reject(Reason.STATION_CHANGE_REQUIRES_BLE), decide(known.copy(station = "station-new"), ble = false))
    }

    @Test
    fun `same station cannot disguise changed hardware on BLE or LAN`() {
        for (ble in listOf(true, false)) {
            assertEquals(Decision.Reject(Reason.HARDWARE_CHANGED), decide(known.copy(deviceId = "a4cb8fd77500"), ble))
        }
    }

    @Test
    fun `a changed endpoint cannot be silently reassigned even with the same reported hardware`() {
        assertEquals(Decision.Reject(Reason.ENDPOINT_CHANGED), decide(known.copy(address = "AA:BB:CC:DD:EE:02"), ble = true))
    }

    @Test
    fun `missing pinned hardware identity rejects a reconnect`() {
        assertEquals(Decision.Reject(Reason.HARDWARE_MISSING), decide(known.copy(deviceId = null), ble = true))
    }

    @Test
    fun `malformed hardware identity rejects a reconnect`() {
        for (id in listOf("", "6b40", "441bf6e26b4z", "441bf6e26b400")) {
            assertEquals(Decision.Reject(Reason.HARDWARE_MALFORMED), decide(known.copy(deviceId = id), ble = true))
        }
    }

    @Test
    fun `missing station cannot be used to collect files`() {
        for (station in listOf(null, "", " ")) {
            assertEquals(Decision.Reject(Reason.STATION_MISSING), decide(known.copy(station = station), ble = true))
        }
    }

    @Test
    fun `address and full hardware hex comparisons ignore case`() {
        assertEquals(Decision.Accept(), decide(known.copy(address = known.address.lowercase(), deviceId = known.deviceId!!.uppercase()), ble = false))
    }

    @Test
    fun `first enrollment requires owner approved BLE but optional dev remains compatible`() {
        for (deviceId in listOf(null, known.deviceId)) {
            val info = known.copy(deviceId = deviceId)
            assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(null, info, authenticatedBle = true))
            assertEquals(Decision.Reject(Reason.ENROLLMENT_REQUIRES_BLE), DeviceContinuityPolicy.evaluate(null, info, authenticatedBle = false))
        }
    }

    @Test
    fun `an empty saved scan row is still a first enrollment`() {
        val scanned = known.copy(deviceId = null, station = null)
        assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(scanned, known, authenticatedBle = true))
        assertEquals(Decision.Reject(Reason.ENROLLMENT_REQUIRES_BLE), DeviceContinuityPolicy.evaluate(scanned, known, authenticatedBle = false))
    }

    @Test
    fun `legacy devices without dev may collect same station but cannot automatically link a new one`() {
        val legacy = known.copy(deviceId = null)
        assertEquals(Decision.Accept(), DeviceContinuityPolicy.evaluate(legacy, legacy, authenticatedBle = false))
        assertEquals(Decision.Reject(Reason.STATION_CHANGE_REQUIRES_HARDWARE), DeviceContinuityPolicy.evaluate(legacy, legacy.copy(station = "station-new"), authenticatedBle = true))
        assertEquals(Decision.Reject(Reason.STATION_CHANGE_REQUIRES_HARDWARE), DeviceContinuityPolicy.evaluate(legacy, known.copy(station = "station-new"), authenticatedBle = true))
    }

    private fun decide(observed: Identity, ble: Boolean): Decision = DeviceContinuityPolicy.evaluate(known, observed, authenticatedBle = ble)
}

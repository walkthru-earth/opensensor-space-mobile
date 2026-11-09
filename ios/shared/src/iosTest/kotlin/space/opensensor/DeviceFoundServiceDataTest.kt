package space.opensensor

import space.opensensor.data.protocol.Advertisement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Core Bluetooth service data reaches the shared state as the advert flags (#272). */
class DeviceFoundServiceDataTest {
    private val id = "22222222-2222-2222-2222-222222222222"

    private fun advert(version: Int, flags: Int, size: Int = 8) =
        ByteArray(size).also {
            it[0] = version.toByte()
            it[1] = flags.toByte()
        }.toNSData()

    @Test fun aVersionOneAdvertCarriesItsFlags() {
        val state = CompanionState()
        state.deviceFound(id, "OS-b", -50, advert(Advertisement.VERSION, Advertisement.FLAG_SETUP))
        assertEquals(Advertisement.FLAG_SETUP, state.devices.single().advertFlags)
    }

    @Test fun noServiceDataOrAnUnknownAdvertCarriesNoFlags() {
        val state = CompanionState()
        state.deviceFound(id, "OS-b", -50, serviceData = null)
        assertNull(state.devices.single().advertFlags)
        state.deviceFound(id, "OS-b", -50, advert(Advertisement.VERSION + 1, Advertisement.FLAG_SETUP))
        assertNull(state.devices.single().advertFlags, "an unknown version is not read")
        state.deviceFound(id, "OS-b", -50, advert(Advertisement.VERSION, Advertisement.FLAG_SETUP, size = 4))
        assertNull(state.devices.single().advertFlags, "a short payload is not read")
    }
}

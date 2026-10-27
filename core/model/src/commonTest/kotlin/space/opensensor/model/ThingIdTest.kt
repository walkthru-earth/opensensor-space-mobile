package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ThingIdTest {
    @Test
    fun keyRoundTrips() {
        val ble = ThingId.BleAddress("AA:BB:CC:DD:EE:FF")
        val phone = ThingId.PhoneInstall("0f8e7d6c-5b4a-4321-8765-0123456789ab")
        assertEquals("ble:AA:BB:CC:DD:EE:FF", ble.key)
        assertEquals(ble, ThingId.parse(ble.key))
        assertEquals(phone, ThingId.parse(phone.key))
    }

    @Test
    fun kindsNeverCollide() {
        assertNotEquals<ThingId>(ThingId.BleAddress("x"), ThingId.PhoneInstall("x"))
    }

    @Test
    fun otherTextIsNoThingId() {
        assertNull(ThingId.parse("AA:BB:CC:DD:EE:FF"))
        assertNull(ThingId.parse("ble:"))
        assertNull(ThingId.parse("phone: "))
        assertFailsWith<IllegalArgumentException> { ThingId.BleAddress(" ") }
    }
}

package space.opensensor

import kotlin.test.Test
import kotlin.test.assertEquals

class PeripheralLeasesTest {
    private val closed = mutableListOf<String>()
    private val leases = PeripheralLeases { closed += it }

    @Test
    fun poppingTodayAloneClosesOnce() {
        leases.acquire("A1")
        leases.release("A1")
        assertEquals(listOf("A1"), closed)
    }

    @Test
    fun backFromDeviceKeepsTheLinkWhileTodayIsOpen() {
        leases.acquire("A1") // Today
        leases.acquire("A1") // Device
        leases.release("A1") // Back to Today
        assertEquals(emptyList(), closed)
        leases.release("A1") // Back from Today
        assertEquals(listOf("A1"), closed)
    }

    @Test
    fun idsMatchWhateverTheirCaseAndAnExtraReleaseIsIgnored() {
        leases.acquire("a1")
        leases.acquire("A1")
        leases.release("A1")
        leases.release("a1")
        leases.release("a1")
        assertEquals(listOf("a1"), closed)
    }

    @Test
    fun sensorsAreCountedApart() {
        leases.acquire("A1")
        leases.acquire("B2")
        leases.release("B2")
        assertEquals(listOf("B2"), closed)
    }
}

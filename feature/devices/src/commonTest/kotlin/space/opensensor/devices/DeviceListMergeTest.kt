package space.opensensor.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceListMergeTest {
    private val a = KnownSensor("A", "Kitchen", "st-a", lastSeenMs = 100, hasWifiAccess = true)
    private val b = KnownSensor("B", "Balcony", "st-b", lastSeenMs = 300, hasWifiAccess = false)
    private val c = KnownSensor("C", null, null, lastSeenMs = null, hasWifiAccess = false)

    @Test
    fun nearbySortsBySignalAndMatchesKnownById() {
        val list = DeviceListMerge.merge(
            nearby = listOf(NearbySensor("X", "New", -80), NearbySensor("A", null, -50, bonded = true)),
            known = listOf(a),
            wifi = emptyList(),
            active = emptyList(),
            archive = emptyMap(),
        )
        assertEquals(listOf("A", "X"), list.nearby.map { it.id })
        val rowA = list.nearby.first()
        assertTrue(rowA.known)
        assertTrue(rowA.bonded)
        assertEquals("Kitchen", rowA.name, "the known name fills a nameless advertisement")
        assertEquals("st-a", rowA.thing)
        assertFalse(list.nearby[1].known)
        assertTrue(list.known.isEmpty(), "a nearby known sensor is listed once")
        assertEquals(1, list.knownCount)
    }

    @Test
    fun knownNotNearbySortsByLastSeenWithUnknownLast() {
        val list = DeviceListMerge.merge(emptyList(), listOf(a, c, b), emptyList(), emptyList(), emptyMap())
        assertEquals(listOf("B", "A", "C"), list.known.map { it.id })
        assertTrue(list.known.all { it.known && !it.nearby })
        assertEquals(3, list.knownCount)
    }

    @Test
    fun wifiTagsKnownThingsAndListsTheRestOnTheirOwn() {
        val list = DeviceListMerge.merge(
            nearby = emptyList(),
            known = listOf(a, b),
            wifi = listOf(WifiSensor("aq-a", "st-a"), WifiSensor("aq-b", "st-b"), WifiSensor("aq-z", "st-z"), WifiSensor("aq-n", null)),
            active = emptyList(),
            archive = emptyMap(),
        )
        assertEquals(WifiTag.WITH_ACCESS, list.known.first { it.id == "A" }.wifi)
        assertEquals(WifiTag.NO_ACCESS, list.known.first { it.id == "B" }.wifi)
        assertEquals(listOf("aq-n", "aq-z"), list.wifiOnly.map { it.name })
    }

    @Test
    fun aKnownSensorOffTheWifiHasNoTag() {
        val list = DeviceListMerge.merge(emptyList(), listOf(a), listOf(WifiSensor("aq-z", "st-z")), emptyList(), emptyMap())
        assertNull(list.known.single().wifi)
    }

    @Test
    fun archiveIsKeyedByThing() {
        val glance = ArchiveGlance(files = 4, lastSyncedMs = 1, lastDataUtcNs = 2)
        val list = DeviceListMerge.merge(listOf(NearbySensor("A", "Kitchen", -60)), listOf(a, c), emptyList(), emptyList(), mapOf("st-a" to glance))
        assertEquals(glance, list.nearby.single().archive)
        assertNull(list.known.single().archive, "no thing, no archive")
    }

    @Test
    fun activeLinksAreKeptOnceAndDoNotHideRows() {
        val link = ActiveLink("A", "Kitchen", LinkPhase.CONNECTED)
        val list = DeviceListMerge.merge(emptyList(), listOf(a), emptyList(), listOf(link, link), emptyMap())
        assertEquals(listOf(link), list.active)
        assertEquals(listOf("A"), list.known.map { it.id })
    }

    @Test
    fun emptyInputsGiveAnEmptyList() {
        assertTrue(DeviceListMerge.merge(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()).isEmpty)
    }
}

package space.opensensor.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.Advertisement
import space.opensensor.data.protocol.DeviceConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ThingSetupWatchTest {
    private val cell = "8828308281fffff"

    private fun page(json: String) = DeviceConfig.parse(json)

    private val unset = page("""{"location":{"cell":"","resolution":8},"thing":{"mobility":"fixed"}}""")
    private val done = page("""{"location":{"cell":"$cell","resolution":8},"thing":{"mobility":"fixed","setup":0}}""")

    @Test
    fun `an advert with bit 6 needs setup`() {
        val needs = ThingSetupWatch.decide(listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 10)), emptyList(), emptySet())
        assertEquals(listOf(SetupNeed("AA", opened = false)), needs)
    }

    @Test
    fun `an advert without the flag and no page claims nothing`() {
        val needs = ThingSetupWatch.decide(listOf(SetupSighting("AA", 0, 10), SetupSighting("BB", null, 10)), emptyList(), emptySet())
        assertEquals(emptyList(), needs)
    }

    @Test
    fun `page 1 decides for an advert without the flag`() {
        val needs = ThingSetupWatch.decide(listOf(SetupSighting("AA", 0, 10)), listOf(ThingSetupWatch.Page("aa", unset, 5)), emptySet())
        assertEquals(listOf(SetupNeed("AA", opened = false)), needs)
    }

    @Test
    fun `an advert older than the page read does not undo it`() {
        val sightings = listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 10))
        assertEquals(emptyList(), ThingSetupWatch.decide(sightings, listOf(ThingSetupWatch.Page("AA", done, 10)), emptySet()))
        assertEquals(emptyList(), ThingSetupWatch.decide(listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, null)), listOf(ThingSetupWatch.Page("AA", done, 10)), emptySet()))
    }

    @Test
    fun `an advert newer than the page read counts`() {
        val needs = ThingSetupWatch.decide(listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 11)), listOf(ThingSetupWatch.Page("AA", done, 10)), emptySet())
        assertEquals(listOf(SetupNeed("AA", opened = false)), needs)
    }

    @Test
    fun `a page read alone counts for a sensor not in the scan`() {
        val needs = ThingSetupWatch.decide(emptyList(), listOf(ThingSetupWatch.Page("CC", unset, 1), ThingSetupWatch.Page("DD", done, 1)), emptySet())
        assertEquals(listOf(SetupNeed("CC", opened = false)), needs)
    }

    @Test
    fun `sighted sensors keep their order and come first`() {
        val sightings = listOf(SetupSighting("BB", Advertisement.FLAG_SETUP, 1), SetupSighting("AA", Advertisement.FLAG_SETUP, 1), SetupSighting("bb", Advertisement.FLAG_SETUP, 1))
        val needs = ThingSetupWatch.decide(sightings, listOf(ThingSetupWatch.Page("CC", unset, 1)), setOf("aa"))
        assertEquals(listOf(SetupNeed("BB", false), SetupNeed("AA", true), SetupNeed("CC", false)), needs)
    }

    @Test
    fun `opened spends the automatic open until the session ends`() = runTest {
        val watch = ThingSetupWatch()
        val scan = MutableStateFlow(listOf(SetupSighting("AA:BB", Advertisement.FLAG_SETUP, 5)))
        assertEquals(listOf(SetupNeed("AA:BB", false)), watch.needs(scan).first())
        watch.opened("aa:bb")
        assertEquals(listOf(SetupNeed("AA:BB", true)), watch.needs(scan).first())
        watch.endSession(4)
        assertEquals(listOf(SetupNeed("AA:BB", false)), watch.needs(scan).first())
    }

    @Test
    fun `a page read that says done clears the need and the session end forgets it`() = runTest {
        val watch = ThingSetupWatch()
        val scan = MutableStateFlow(listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 5)))
        watch.pageRead("AA", done, 6)
        assertEquals(emptyList(), watch.needs(scan).first())
        watch.endSession(4)
        assertEquals(listOf(SetupNeed("AA", false)), watch.needs(scan).first())
    }

    @Test
    fun `a sighting kept from the last session claims nothing until heard again`() = runTest {
        val watch = ThingSetupWatch()
        val scan = MutableStateFlow(listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 5)))
        watch.endSession(5)
        assertEquals(emptyList(), watch.needs(scan).first())
        scan.value = listOf(SetupSighting("AA", Advertisement.FLAG_SETUP, 6))
        assertEquals(listOf(SetupNeed("AA", false)), watch.needs(scan).first())
    }
}

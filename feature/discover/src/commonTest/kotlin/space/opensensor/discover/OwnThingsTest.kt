package space.opensensor.discover

import kotlin.test.Test
import kotlin.test.assertEquals

class OwnThingsTest {
    private val place = OwnThing("a", "", "85283473fffffff", 1L)

    @Test
    fun aForgottenThingLosesItsPin() {
        assertEquals(emptyList(), knownOwnThings(listOf(place), names = mapOf("b" to "Other")))
    }

    @Test
    fun aKnownThingTakesItsName() {
        assertEquals(listOf(place.copy(name = "Rooftop")), knownOwnThings(listOf(place), mapOf("a" to " Rooftop ")))
        assertEquals(listOf(place), knownOwnThings(listOf(place), mapOf("a" to null)))
    }
}

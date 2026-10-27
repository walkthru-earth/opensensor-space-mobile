package space.opensensor.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.DeviceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThingPlacesTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val cell = "881f1d4887fffff"

    private fun page(json: String) = DeviceConfig.parse(json)

    @Test
    fun aFixedThingIsPlacedByItsCellOnly() {
        val place = ThingPlace.of(thing, page("""{"location":{"cell":"$cell","resolution":8,"lat":52.5,"lon":13.4},"thing":{"mobility":"fixed"}}"""), 7)
        assertEquals(ThingPlace(thing, cell, 7), place)
    }

    @Test
    fun aMovingThingOrAnUnknownMobilityIsNeverPlaced() {
        assertNull(ThingPlace.of(thing, page("""{"location":{"cell":"$cell"},"thing":{"mobility":"moving"}}"""), 1))
        assertNull(ThingPlace.of(thing, page("""{"location":{"cell":"$cell"}}"""), 1))
        assertNull(ThingPlace.of(thing, page("""{"location":{"cell":"$cell"},"thing":{"mobility":"drifting"}}"""), 1))
    }

    @Test
    fun aMissingOrMalformedCellIsNeverPlaced() {
        for (bad in listOf("", "  ", "not-a-cell", "881F1D4887FFFFF0", "881f1d4887ffff", "0")) {
            assertNull(ThingPlace.of(thing, page("""{"location":{"cell":"$bad"},"thing":{"mobility":"fixed"}}"""), 1), bad)
        }
        assertNull(ThingPlace.of(thing, page("""{"thing":{"mobility":"fixed"}}"""), 1))
    }

    @Test
    fun anUppercaseCellIsStoredInTheFeedForm() {
        val place = ThingPlace.of(thing, page("""{"location":{"cell":"881F1D4887FFFFF"},"thing":{"mobility":"fixed"}}"""), 1)
        assertEquals(cell, place?.cell)
    }

    @Test
    fun aReadPageReplacesOrClearsThePlace() = runTest {
        val places = MemoryPlaces()
        places.recordFrom(thing, page("""{"location":{"cell":"$cell"},"thing":{"mobility":"fixed"}}"""), 1)
        assertEquals(listOf(ThingPlace(thing, cell, 1)), places.observeAll().first())
        // The Thing now moves, so its pin goes.
        places.recordFrom(thing, page("""{"location":{"cell":"$cell"},"thing":{"mobility":"moving"}}"""), 2)
        assertEquals(emptyList(), places.observeAll().first())
    }

    @Test
    fun aFailingStoreNeverFailsTheRead() = runTest {
        val broken = object : ThingPlaces by MemoryPlaces() {
            override suspend fun put(place: ThingPlace) = throw IllegalStateException("disk full")
        }
        broken.recordFrom(thing, page("""{"location":{"cell":"$cell"},"thing":{"mobility":"fixed"}}"""), 1)
    }

    private class MemoryPlaces : ThingPlaces {
        val rows = MutableStateFlow<Map<String, ThingPlace>>(emptyMap())

        override fun observeAll() = MutableStateFlow(rows.value.values.toList())

        override suspend fun put(place: ThingPlace) {
            rows.value = rows.value + (place.thing to place)
        }

        override suspend fun remove(thing: String) {
            rows.value = rows.value - thing
        }
    }
}

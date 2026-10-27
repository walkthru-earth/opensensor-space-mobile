package space.opensensor.data.local.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.archive.RoomThingPlaces
import space.opensensor.data.sync.ThingPlace
import kotlin.test.Test
import kotlin.test.assertEquals

class ThingPlaceStoreTest {
    @Test
    fun aPlaceIsReplacedPerThingAndRemovedAlone() = runTest {
        val db = inMemoryAppDatabase()
        try {
            val places = RoomThingPlaces(db.thingPlaceDao())
            places.put(ThingPlace("thing-a", "881f1d4887fffff", 1))
            places.put(ThingPlace("thing-b", "881f1d4881fffff", 2))
            places.put(ThingPlace("thing-a", "881f1d4885fffff", 3))
            assertEquals(
                listOf(ThingPlace("thing-a", "881f1d4885fffff", 3), ThingPlace("thing-b", "881f1d4881fffff", 2)),
                places.observeAll().first(),
            )
            places.remove("thing-a")
            places.remove("thing-unknown")
            assertEquals(listOf(ThingPlace("thing-b", "881f1d4881fffff", 2)), places.observeAll().first())
        } finally {
            db.close()
        }
    }
}

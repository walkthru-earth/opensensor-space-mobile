package space.opensensor.data.archive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.opensensor.data.local.database.ThingPlaceDao
import space.opensensor.data.local.database.ThingPlaceEntity
import space.opensensor.data.sync.ThingPlace
import space.opensensor.data.sync.ThingPlaces

/** The shared [ThingPlaces] over the `thing_place` table, on both phones. */
class RoomThingPlaces(private val dao: ThingPlaceDao) : ThingPlaces {
    override fun observeAll(): Flow<List<ThingPlace>> = dao.observeAll().map { rows -> rows.map { ThingPlace(it.thing, it.cell, it.updatedEpochMs) } }

    override suspend fun put(place: ThingPlace) = dao.put(ThingPlaceEntity(place.thing, place.cell, place.updatedEpochMs))

    override suspend fun remove(thing: String) = dao.remove(thing)
}

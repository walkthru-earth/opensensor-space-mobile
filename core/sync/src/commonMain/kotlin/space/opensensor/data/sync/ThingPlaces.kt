package space.opensensor.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.model.H3Cell

/**
 * Where one of the user's own Things stands, as an H3 [cell] (#226). The cell is the lowercase hex
 * form the public feed uses. [updatedEpochMs] is when the phone last read it. A place never holds
 * coordinates, so the cell centre the sensor echoes is not kept.
 *
 * A fixed Thing stands at its configured cell. A [moving] Thing stands at the last fresh cell of its
 * own GNSS receiver (`location.gnss_cell`), and its [cell] is empty until it reports one (#263). The
 * phone never supplies a cell for it (#264).
 */
data class ThingPlace(val thing: String, val cell: String, val updatedEpochMs: Long, val moving: Boolean = false) {
    /** True when [cell] names a cell, so Discover can draw a pin. */
    val placed: Boolean get() = cell.isNotEmpty()

    companion object {
        /**
         * The place a `GET_CONFIG` location [page] gives [thing], or null when it gives none. A fixed
         * Thing with a well formed cell stands at it. A moving Thing stands at its fresh GNSS cell, or
         * gets an empty cell when it has no fix or no receiver, never its configured cell. A mobility
         * this build does not know and a missing one give no place.
         */
        fun of(thing: String, page: DeviceConfig, nowEpochMs: Long): ThingPlace? {
            if (thing.isBlank()) return null
            return when (page.thing?.thingMobility) {
                ThingMobility.FIXED -> {
                    val cell = page.location?.cell?.trim()?.lowercase() ?: return null
                    if (H3Cell.isCellHex(cell)) ThingPlace(thing, cell, nowEpochMs) else null
                }
                ThingMobility.MOVING -> ThingPlace(thing, page.location?.gnssCell.orEmpty(), nowEpochMs, moving = true)
                null -> null
            }
        }
    }
}

/**
 * The phone's own Thing places, one per Thing UUID. Both phones keep them in the Room `thing_place`
 * table. Only Discover reads them, and they never leave the phone.
 */
interface ThingPlaces {
    /** Every place, kept current. */
    fun observeAll(): Flow<List<ThingPlace>>

    /** The place of [thing], or null. */
    suspend fun get(thing: String): ThingPlace? = observeAll().first().firstOrNull { it.thing == thing }

    /** Adds [place] or replaces the one its Thing had. */
    suspend fun put(place: ThingPlace)

    /** Drops the place of [thing], if any. */
    suspend fun remove(thing: String)

    /** Keeps nothing. */
    object None : ThingPlaces {
        override fun observeAll(): Flow<List<ThingPlace>> = flowOf(emptyList())
        override suspend fun put(place: ThingPlace) = Unit
        override suspend fun remove(thing: String) = Unit
    }
}

/**
 * Keeps the place of [thing] in step with a location [page] the sensor just returned. A page with no
 * place drops the old one. A moving Thing with no fresh fix keeps the last GNSS cell it reported, and
 * a Thing that became moving drops its configured cell. A store failure is only printed, because the
 * read itself succeeded.
 */
suspend fun ThingPlaces.recordFrom(thing: String, page: DeviceConfig, nowEpochMs: Long) {
    try {
        val place = ThingPlace.of(thing, page, nowEpochMs)
        when {
            place == null -> remove(thing)
            place.moving && !place.placed && get(thing)?.let { it.moving && it.placed } == true -> Unit
            else -> put(place)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("thing place not saved: ${e::class.simpleName}")
    }
}

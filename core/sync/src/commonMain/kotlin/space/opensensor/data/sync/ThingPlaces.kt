package space.opensensor.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.ThingMobility

/**
 * Where one of the user's own fixed Things stands, as the H3 [cell] saved on it (#226). The cell is
 * the lowercase hex form the public feed uses. [updatedEpochMs] is when the phone last read it. A
 * place never holds coordinates, so the cell centre the sensor echoes is not kept.
 */
data class ThingPlace(val thing: String, val cell: String, val updatedEpochMs: Long) {
    companion object {
        /**
         * The place a `GET_CONFIG` location [page] gives [thing], or null when it gives none. Only a
         * Thing that says it is fixed and names a well formed cell has one. A moving Thing, a mobility
         * this build does not know and a missing one all show no pin.
         */
        fun of(thing: String, page: DeviceConfig, nowEpochMs: Long): ThingPlace? {
            if (thing.isBlank()) return null
            if (page.thing?.thingMobility != ThingMobility.FIXED) return null
            val cell = page.location?.cell?.trim()?.lowercase() ?: return null
            if (!isCellHex(cell)) return null
            return ThingPlace(thing, cell, nowEpochMs)
        }

        /** A 64-bit H3 cell in hex, mode 1. */
        private fun isCellHex(hex: String): Boolean {
            if (hex.length != 15 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
            val index = hex.toULong(16).toLong()
            return (index ushr 59) and 0xF == 1L
        }
    }
}

/**
 * The phone's own fixed Thing places, one per Thing UUID. Both phones keep them in the Room
 * `thing_place` table. Only Discover reads them, and they never leave the phone.
 */
interface ThingPlaces {
    /** Every place, kept current. */
    fun observeAll(): Flow<List<ThingPlace>>

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
 * place drops the old one, so a Thing that became moving loses its pin. A store failure is only
 * printed, because the read itself succeeded.
 */
suspend fun ThingPlaces.recordFrom(thing: String, page: DeviceConfig, nowEpochMs: Long) {
    try {
        val place = ThingPlace.of(thing, page, nowEpochMs)
        if (place != null) put(place) else remove(thing)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("thing place not saved: ${e::class.simpleName}")
    }
}

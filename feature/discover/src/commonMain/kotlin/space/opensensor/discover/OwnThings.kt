package space.opensensor.discover

import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index

/**
 * One of the user's own fixed Things, as the host hands it to Discover (#226). [key] is the Thing
 * UUID, [cell] the H3 cell saved on it in lowercase hex, [updatedEpochMs] when the phone last read
 * that cell. The host never passes coordinates. The pin stands at the cell centre.
 */
data class OwnThing(val key: String, val name: String, val cell: String, val updatedEpochMs: Long)

/** An own Thing placed at its cell centre, for the map source and the sheet list. */
data class OwnPin(val thing: OwnThing, val lat: Double, val lng: Double)

/** The own Things layer, its pins sorted by name and their GeoJSON. */
internal data class OwnLayer(val pins: List<OwnPin>, val geoJson: String) {
    companion object {
        val EMPTY = OwnLayer(emptyList(), DiscoverUiState.EMPTY_GEOJSON)
    }
}

/**
 * Builds the own Things point source. It is a source of its own, never mixed into the public cells.
 * Each point carries only its index in the sorted pins, so no Thing UUID or name reaches the map.
 */
internal object OwnThingGeoJson {
    fun build(h3: H3Grid, things: List<OwnThing>): OwnLayer {
        if (!h3.available || things.isEmpty()) return OwnLayer.EMPTY
        val pins = things.mapNotNull { t ->
            val cell = cellOf(t.cell) ?: return@mapNotNull null
            val centre = h3.cellToLatLng(cell)
            OwnPin(t, centre.lat, centre.lng)
        }.sortedWith(compareBy({ it.thing.name.lowercase() }, { it.thing.key }))
        if (pins.isEmpty()) return OwnLayer.EMPTY
        val json = buildString {
            append("{\"type\":\"FeatureCollection\",\"features\":[")
            pins.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"type\":\"Feature\",\"properties\":{\"i\":").append(i).append("},")
                append("\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(p.lng).append(',').append(p.lat).append("]}}")
            }
            append("]}")
        }
        return OwnLayer(pins, json)
    }

    /** The cell [hex] names, or null when it is not a 64-bit H3 cell (mode 1). */
    private fun cellOf(hex: String): Long? {
        val h = hex.trim().lowercase()
        if (h.length != 15 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val cell = H3Index.fromHex(h)
        return cell.takeIf { (it ushr 59) and 0xF == 1L }
    }
}

/**
 * The own Things Discover shows, from the saved [places] and the Things the phone still knows. A
 * place whose Thing is not in [names] (Thing UUID to name) shows nothing, so a forgotten sensor
 * loses its pin. A Thing with no name keeps a blank one, and the sheet gives it a generic label.
 */
fun knownOwnThings(places: List<OwnThing>, names: Map<String, String?>): List<OwnThing> =
    places.mapNotNull { p -> if (p.key !in names) null else p.copy(name = names[p.key]?.trim().orEmpty()) }

package space.opensensor.discover

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.LatestReading
import space.opensensor.data.history.LatestReadings
import space.opensensor.publicfeed.CellRow
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import kotlin.time.Instant

/**
 * One of the user's own fixed Things, as the host hands it to Discover (#226). [key] is the Thing
 * UUID, [cell] the H3 cell saved on it in lowercase hex, [updatedEpochMs] when the phone last read
 * that cell. The host never passes coordinates. The pin stands at the cell centre.
 */
data class OwnThing(val key: String, val name: String, val cell: String, val updatedEpochMs: Long)

/** An own Thing placed at its cell centre, for the map source and the sheet list. [cell] is its H3 cell. */
data class OwnPin(val thing: OwnThing, val lat: Double, val lng: Double, val cell: Long)

/**
 * What the phone knows of one own Thing now, keyed by its Thing UUID (#273). [reading] is the newest
 * 15 minute reading in this phone's history, null before the first sync. [live] is true while the
 * phone holds a link to the sensor. [shared] is true once the owner shared its data and a file of it
 * went up. All of it is local. None of it is ever added to or taken from a public cell.
 */
data class OwnStatus(val reading: LatestReading? = null, val live: Boolean = false, val shared: Boolean = false)

/**
 * The [OwnStatus] of each own Thing on both phones. [things] are the Thing UUIDs with a saved place,
 * [live] the ones the phone holds a link to and [uploaded] the ones with a file that went up. Each
 * Thing's newest reading comes from [tiers], this phone's history, so it works with no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeOwnStatus(tiers: HistoryTiers, things: Flow<Set<String>>, live: Flow<Set<String>>, uploaded: Flow<Set<String>>): Flow<Map<String, OwnStatus>> {
    val readings = things.distinctUntilChanged().flatMapLatest { keys ->
        if (keys.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(keys.map { key -> LatestReadings.observe(tiers, key).map { key to it } }) { it.toMap() }
        }
    }
    return combine(readings, live, uploaded) { latest, linked, shared ->
        latest.mapValues { (key, reading) -> OwnStatus(reading, live = key in linked, shared = key in shared) }
    }.distinctUntilChanged()
}

/** How old an own reading is, the steps the sheet names. */
internal enum class AgeStep { JUST_NOW, MINUTES, HOURS, DAYS }

/** What an own pin's row says after its reading. */
internal sealed interface OwnFreshness {
    /** The phone is linked to the sensor now. */
    data object Live : OwnFreshness

    /** The newest reading on this phone ended [count] [step] ago. */
    data class Ago(val step: AgeStep, val count: Long) : OwnFreshness

    /** Not linked and no reading on this phone yet. */
    data object None : OwnFreshness
}

/** The step and count of an age of [ageMs]. A minute or less, or a clock behind, is just now. */
internal fun ageOf(ageMs: Long): Pair<AgeStep, Long> {
    val minutes = ageMs / 60_000
    return when {
        minutes < 1 -> AgeStep.JUST_NOW to 0L
        minutes < 60 -> AgeStep.MINUTES to minutes
        minutes < 24 * 60 -> AgeStep.HOURS to minutes / 60
        else -> AgeStep.DAYS to minutes / (24 * 60)
    }
}

/** Live while linked, else the age of the newest reading at [nowMs], else [OwnFreshness.None]. */
internal fun ownFreshness(status: OwnStatus?, nowMs: Long): OwnFreshness {
    if (status?.live == true) return OwnFreshness.Live
    val reading = status?.reading ?: return OwnFreshness.None
    val (step, count) = ageOf(nowMs - reading.endEpochS * 1000)
    return OwnFreshness.Ago(step, count)
}

/**
 * True when [row] is a cell of a feed that folds in shared sensors and an own Thing that shared its
 * data stands inside it. Only then may the sheet say the cell includes the user's sensor. It only
 * reads the row's cell, never its values, so own data is never added to or taken from a public mean.
 */
internal fun DiscoverUiState.includesOwn(row: CellRow?): Boolean {
    if (!foldsShared || row == null) return false
    val res = H3Index.resolution(row.h3)
    return own.any { pin ->
        ownStatus[pin.thing.key]?.shared == true && H3Index.resolution(pin.cell) >= res && H3Index.parent(pin.cell, res) == row.h3
    }
}

/** The end of the newest own reading, in epoch seconds, for the sources info. Null with none. */
internal fun DiscoverUiState.newestOwnReadingS(): Long? =
    own.mapNotNull { ownStatus[it.thing.key]?.reading?.endEpochS }.maxOrNull()

/** The phone's calendar day of [epochMs], for the newest own reading. */
internal fun localDay(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

/** The UTC day of [epochMs], for the bundled sample, whose rows are dated in UTC. */
internal fun utcDay(epochMs: Long): LocalDate = localDay(epochMs, TimeZone.UTC)

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
            OwnPin(t, centre.lat, centre.lng, cell)
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

package space.opensensor.discover

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.opensensor.data.history.CellTrack
import space.opensensor.data.history.DaySpan
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.LatestReading
import space.opensensor.data.history.LocationSource
import space.opensensor.data.history.Resolution
import space.opensensor.data.history.TierBucket
import space.opensensor.data.history.TrackRow
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnThingsTest {
    init {
        AirFamily.install()
    }

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

    @Test
    fun anAgeIsNamedInItsLargestWholeStep() {
        assertEquals(AgeStep.JUST_NOW to 0L, ageOf(59_999))
        // A phone clock behind the reading is just now, never a negative age.
        assertEquals(AgeStep.JUST_NOW to 0L, ageOf(-5 * 60_000))
        assertEquals(AgeStep.MINUTES to 1L, ageOf(60_000))
        assertEquals(AgeStep.MINUTES to 59L, ageOf(60 * 60_000 - 1))
        assertEquals(AgeStep.HOURS to 1L, ageOf(60 * 60_000))
        assertEquals(AgeStep.HOURS to 23L, ageOf(24 * 60 * 60_000L - 1))
        assertEquals(AgeStep.DAYS to 3L, ageOf(3 * 24 * 60 * 60_000L + 5))
    }

    @Test
    fun anOwnPinIsLiveWhileLinkedElseAsOldAsItsNewestReading() {
        val reading = LatestReading(endEpochS = 1_000, mean = 7f, metric = AirMetrics.PM25, displayStep = 1.0)
        val now = 1_000_000L + 12 * 60_000
        assertEquals(OwnFreshness.Ago(AgeStep.MINUTES, 12), ownFreshness(OwnStatus(reading), now))
        // Linked, Live wins over the age of the newest stored reading.
        assertEquals(OwnFreshness.Live, ownFreshness(OwnStatus(reading, live = true), now))
        assertEquals(OwnFreshness.Live, ownFreshness(OwnStatus(live = true), now))
        // Before its first sync and unlinked, a pin says nothing after its name.
        assertEquals(OwnFreshness.None, ownFreshness(OwnStatus(), now))
        assertEquals(OwnFreshness.None, ownFreshness(null, now))
        assertEquals(OwnFreshness.Ago(AgeStep.DAYS, 2), ownFreshness(OwnStatus(reading), 1_000_000L + 2 * 86_400_000L))
    }

    @Test
    fun ownStatusJoinsTheLocalReadingTheLinkAndTheUploadOfEachThing() = runTest {
        val day = 10 * 86_400L
        // Thing a has a quarter of PM2.5 on this phone, thing b has nothing yet.
        val tiers = object : HistoryTiers {
            override fun observe(thing: String, metricId: String, tier: Resolution, fromS: Long, toS: Long): Flow<List<TierBucket>> =
                flowOf(if (thing == "a" && metricId == AirMetrics.PM25.id) listOf(TierBucket(day, 90, 90, 0, 9f, 9f, 9f, 10_000)) else emptyList())
            override fun observeDays(thing: String) = flowOf(if (thing == "a") DaySpan(1, day, day) else DaySpan())
            override fun observeMetricsWithData(thing: String) = flowOf(if (thing == "a") setOf(AirMetrics.PM25.id) else emptySet())
        }
        val linked = MutableStateFlow(setOf("b"))
        val uploaded = MutableStateFlow(emptySet<String>())
        val out = observeOwnStatus(tiers, flowOf(setOf("a", "b")), linked, uploaded)
        val first = out.first()
        assertEquals(day + 900, first["a"]?.reading?.endEpochS)
        assertEquals(false to false, first["a"]?.let { it.live to it.shared })
        assertEquals(OwnStatus(null, live = true, shared = false), first["b"])
        uploaded.value = setOf("a")
        assertEquals(true, out.first()["a"]?.shared)
        assertEquals(emptyMap(), observeOwnStatus(tiers, flowOf(emptySet()), linked, uploaded).first())
    }

    /** Every cell centres on its own low bits, and every outline is one small triangle. */
    private object IndexGrid : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int) = 0L
        override fun cellToLatLng(cell: Long) = LatLng(0.0, (cell ushr 12 and 0xFFF).toDouble())
        override fun cellToBoundary(cell: Long) = listOf(LatLng(1.0, 1.0), LatLng(2.0, 1.0), LatLng(1.0, 2.0))
        override fun polygonToCells(ring: List<LatLng>, res: Int) = emptyList<Long>()
    }

    private val cellA = 0x8828308281fffffL
    private val cellB = 0x8828308283fffffL
    private val ms = 1_000_000L

    private fun track(vararg rows: TrackRow) = CellTrack.of(rows.toList())

    @Test
    fun aMovingPinStandsAtTheNewerOfItsPlaceAndItsTrack() {
        val moving = OwnThing("m", "Bike", H3Index.toHex(cellA), updatedEpochMs = 10_000L, moving = true)
        // The track's last fix is newer than the read place, so the pin follows the rows.
        val newer = track(TrackRow(9_000 * ms, cellA, 2), TrackRow(20_000 * ms, cellB, 2))
        assertEquals(cellB, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to newer)).pins.single().cell)
        // A track older than the read place leaves the pin at the place.
        val older = track(TrackRow(5_000 * ms, cellB, 2))
        assertEquals(cellA, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to older)).pins.single().cell)
        // A fixed Thing never moves with its rows.
        val fixed = moving.copy(moving = false)
        assertEquals(cellA, OwnThingGeoJson.build(IndexGrid, listOf(fixed), mapOf("m" to newer)).pins.single().cell)
    }

    @Test
    fun aMovingPinKnowsWhenItsPlaceWasReadOrReported() {
        val moving = OwnThing("m", "Bike", H3Index.toHex(cellA), updatedEpochMs = 10_000L, moving = true)
        // The newer fix in the rows dates the pin, else the time the phone read its place.
        val newer = track(TrackRow(9_000 * ms, cellA, 2), TrackRow(20_000 * ms, cellB, 2))
        assertEquals(20_000L, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to newer)).pins.single().placedEpochMs)
        val older = track(TrackRow(5_000 * ms, cellB, 2))
        assertEquals(10_000L, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to older)).pins.single().placedEpochMs)
        // A fixed Thing has no age on the map, and nor does a place read at an unknown time.
        assertNull(OwnThingGeoJson.build(IndexGrid, listOf(moving.copy(moving = false))).pins.single().placedEpochMs)
        assertNull(OwnThingGeoJson.build(IndexGrid, listOf(moving.copy(updatedEpochMs = 0L))).pins.single().placedEpochMs)
    }

    @Test
    fun theAgeGoesOnlyOnTheMovingPinsAndNamesNoThing() {
        val moving = OwnThing("3f6c2a9e-8d41-4b7a-9e2c-5a1d0b7c4e11", "Bike", H3Index.toHex(cellB), updatedEpochMs = 10_000L, moving = true)
        val fixed = OwnThing("f", "Roof", H3Index.toHex(cellA), updatedEpochMs = 10_000L)
        val pins = OwnThingGeoJson.build(IndexGrid, listOf(moving, fixed)).pins
        assertEquals(listOf("Bike", "Roof"), pins.map { it.thing.name })
        val json = OwnThingGeoJson.points(pins, listOf("12 min \"ago\"", null))
        assertEquals(1, Regex("\"age\"").findAll(json).count(), json)
        assertTrue(json.contains("\"age\":\"12 min \\\"ago\\\"\""), "the words are escaped, $json")
        assertFalse(json.contains(moving.key) || json.contains("Bike") || json.contains("Roof"), json)
        // With no ages it is the source the layer builds.
        assertEquals(OwnThingGeoJson.build(IndexGrid, listOf(moving, fixed)).geoJson, OwnThingGeoJson.points(pins))
        assertEquals(DiscoverUiState.EMPTY_GEOJSON, OwnThingGeoJson.points(emptyList()))
    }

    @Test
    fun aMovingThingWithNoFixHasNoPinAndNoTrack() {
        val moving = OwnThing("m", "Bike", "", updatedEpochMs = 10_000L, moving = true)
        // Rows with no location, source 0 or a value that is no H3 cell are rows with no fix.
        val noFix = track(TrackRow(20_000 * ms, null, null), TrackRow(21_000 * ms, cellA, 0), TrackRow(22_000 * ms, 42L, 2))
        val layer = OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to noFix))
        assertEquals(emptyList(), layer.pins)
        assertEquals(DiscoverUiState.EMPTY_GEOJSON, layer.trackGeoJson)
        // A fix in the rows alone places it, never a borrowed cell.
        val fix = track(TrackRow(20_000 * ms, null, null), TrackRow(21_000 * ms, cellB, 2))
        assertEquals(cellB, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to fix)).pins.single().cell)
    }

    @Test
    fun theTrackSourceDrawsEachVisitedCellOnceWithNoKey() {
        val moving = OwnThing("3f6c2a9e-8d41-4b7a-9e2c-5a1d0b7c4e11", "Bike", "", updatedEpochMs = 0L, moving = true)
        val fixed = OwnThing("f", "Roof", H3Index.toHex(cellA), updatedEpochMs = 0L)
        val route = track(TrackRow(1 * ms, cellA, 2), TrackRow(2 * ms, null, null), TrackRow(3 * ms, cellB, 2), TrackRow(4 * ms, cellA, 2))
        val layer = OwnThingGeoJson.build(IndexGrid, listOf(moving, fixed), mapOf(moving.key to route, fixed.key to route))
        assertEquals(2, Regex("\"Polygon\"").findAll(layer.trackGeoJson).count(), "cells A and B once each, for the moving Thing only")
        assertFalse(layer.trackGeoJson.contains(moving.key), "no Thing UUID in the track source")
        assertFalse(layer.trackGeoJson.contains("Bike"), "no Thing name in the track source")
        // A closed ring in longitude, latitude order, its first vertex repeated.
        assertTrue(layer.trackGeoJson.contains("[[[1.0,1.0],[1.0,2.0],[2.0,1.0],[1.0,1.0]]]"), layer.trackGeoJson)
    }

    @Test
    fun aMovingThingNeverStandsAtTheConfiguredCellOfItsFixedHours() {
        // The owner switched it from Fixed to Moving. Its rows from the fixed hours carry the
        // configured cell with source 1, and its later rows have no fix, so it has no pin and no track.
        val moving = OwnThing("m", "Bike", "", updatedEpochMs = 10_000L, moving = true)
        val configured = LocationSource.CONFIGURED.wire
        val rows = track(TrackRow(1_000 * ms, cellA, configured), TrackRow(2_000 * ms, cellA, configured), TrackRow(11_000 * ms, null, null))
        val layer = OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to rows))
        assertEquals(emptyList(), layer.pins)
        assertEquals(DiscoverUiState.EMPTY_GEOJSON, layer.trackGeoJson)
        // A later GNSS or phone fix places it and is the only cell the track outlines.
        val gnss = track(TrackRow(1_000 * ms, cellA, configured), TrackRow(12_000 * ms, cellB, 2))
        val moved = OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to gnss))
        assertEquals(cellB, moved.pins.single().cell)
        assertEquals(1, Regex("\"Polygon\"").findAll(moved.trackGeoJson).count())
        val phone = track(TrackRow(1_000 * ms, cellA, configured), TrackRow(12_000 * ms, cellB, 3))
        assertEquals(cellB, OwnThingGeoJson.build(IndexGrid, listOf(moving), mapOf("m" to phone)).pins.single().cell)
    }

    /** cellA straddles the antimeridian, and cellB has no boundary. */
    private object AntimeridianGrid : H3Grid {
        override fun latLngToCell(lat: Double, lng: Double, res: Int) = 0L
        override fun cellToLatLng(cell: Long) = LatLng(0.0, 179.5)
        override fun cellToBoundary(cell: Long) =
            if (cell == 0x8828308281fffffL) listOf(LatLng(1.0, 179.5), LatLng(2.0, -179.5), LatLng(1.0, -179.25)) else emptyList()
        override fun polygonToCells(ring: List<LatLng>, res: Int) = emptyList<Long>()
    }

    @Test
    fun aTrackCellAcrossTheAntimeridianStaysContinuous() {
        val moving = OwnThing("m", "Boat", "", updatedEpochMs = 0L, moving = true)
        val route = track(TrackRow(1 * ms, cellA, 2), TrackRow(2 * ms, cellB, 2))
        val json = OwnThingGeoJson.build(AntimeridianGrid, listOf(moving), mapOf("m" to route)).trackGeoJson
        assertTrue(json.contains("[[[179.5,1.0],[180.5,2.0],[180.75,1.0],[179.5,1.0]]]"), json)
        assertEquals(1, Regex("\"Polygon\"").findAll(json).count(), "a cell with no boundary is left out")
    }

    @Test
    fun aFixedThingDrawsNoTrackEvenWhenGivenOne() {
        val fixed = OwnThing("f", "Roof", H3Index.toHex(cellA), updatedEpochMs = 0L)
        val route = track(TrackRow(1 * ms, cellA, 2), TrackRow(3 * ms, cellB, 2))
        assertEquals(DiscoverUiState.EMPTY_GEOJSON, OwnThingGeoJson.build(IndexGrid, listOf(fixed), mapOf("f" to route)).trackGeoJson)
    }

    @Test
    fun theTrackSpansTheLast24Hours() {
        assertEquals(24L * 60 * 60 * 1_000_000_000, OWN_TRACK_SPAN_NS)
    }

    @Test
    fun ownTracksAreReadForMovingThingsOnly() = runTest {
        val asked = mutableListOf<String>()
        val route = track(TrackRow(1 * ms, cellA, 2))
        val out = observeOwnTracks(flowOf(setOf("m"))) { key ->
            asked += key
            flowOf(route)
        }
        assertEquals(mapOf("m" to route), out.first())
        assertEquals(listOf("m"), asked)
        assertEquals(emptyMap(), observeOwnTracks(flowOf(emptySet())) { flowOf(route) }.first())
    }
}

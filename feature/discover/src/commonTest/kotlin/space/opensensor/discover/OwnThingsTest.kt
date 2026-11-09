package space.opensensor.discover

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.opensensor.data.history.DaySpan
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.LatestReading
import space.opensensor.data.history.Resolution
import space.opensensor.data.history.TierBucket
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

package space.opensensor.today

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.settings.DetailLevel
import space.opensensor.today.TodayCard.BOARD
import space.opensensor.today.TodayCard.CLOCK
import space.opensensor.today.TodayCard.DEVICE_INFO
import space.opensensor.today.TodayCard.METRICS
import space.opensensor.today.TodayCard.NOW
import space.opensensor.today.TodayCard.SMART_SYNC
import space.opensensor.today.TodayCard.STORAGE
import space.opensensor.today.TodayCard.TODAY_SO_FAR
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem
import space.opensensor.units.formatFixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FamilyPresenterTest {
    init {
        AirFamily.install()
    }

    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = "f4f81218dc80d1a8e6db043197a2f7180c4f075f9e8348df30eaceef44d68370")
    private val crowpanel = DeviceInfo(proto = 3, schema = "crowpanel-epaper-telemetry-v1", dict = "e7279c4edbe87071e4a51889124a43d7ffcd1977c79c8575887ae35bdee10903")
    private val soilSample = LiveSample.parse("""{"sm":0.31,"st":18.5,"pct":80}""")

    private fun state(info: DeviceInfo?, live: LiveSample?, level: DetailLevel) = TodayUiState(TodaySnapshot(info = info, live = live), level)

    private fun soil(level: DetailLevel) = TodayUiState(TodaySnapshot(info = SoilFixture.info, live = soilSample), level, SoilFixture.capabilities())

    @Test
    fun theRegistryPicksAirForAirMoodAndGenericForEverythingElse() {
        assertSame(AirPresenter, HeroRegistry.presenterFor(HeroRegistry.AIR_MOOD))
        assertSame(GenericPresenter, HeroRegistry.presenterFor(null))
        assertSame(GenericPresenter, HeroRegistry.presenterFor("soil-dial"))
        assertSame(AirPresenter, HeroRegistry.presenterFor(null, setOf(AirFamily.PARTICLES)))
    }

    @Test
    fun aBoardWithNoProfileThatShowsPm25GetsTheAirHero() {
        // The CrowPanel has no bundled profile, its PM2.5 property alone makes it air.
        val state = state(crowpanel, null, DetailLevel.SIMPLE)
        assertTrue(AirFamily.PARTICLES in state.capabilities.capabilities)
        assertSame(AirPresenter, state.presenter)
    }

    @Test
    fun theBundledAirBoardsNameTheAirHero() {
        assertSame(AirPresenter, state(waveshare, null, DetailLevel.SIMPLE).presenter)
        assertSame(AirPresenter, state(cores3, null, DetailLevel.SIMPLE).presenter)
    }

    @Test
    fun aSoilBoardShowsSoilCards() {
        val simple = soil(DetailLevel.SIMPLE)
        assertSame(GenericPresenter, simple.presenter)
        assertEquals(LiveSupport.READY, simple.liveSupport)
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC), simple.cards)
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, METRICS, BOARD, STORAGE, DEVICE_INFO), soil(DetailLevel.DETAILED).cards)
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK, METRICS, BOARD, STORAGE, DEVICE_INFO), soil(DetailLevel.DEVELOPER).cards)
    }

    @Test
    fun aServedBoardWithNoReviewedProfileShowsLive() = SoilFixture.served {
        // A verified template is enough for LIVE, with labels, decimals and units from the template.
        val state = state(SoilFixture.info, soilSample, DetailLevel.DETAILED)
        assertEquals(LiveSupport.READY, state.liveSupport)
        assertEquals(SoilFixture.MOISTURE, state.capabilities.thing?.primary?.name)
        assertEquals(0.31, state.reading?.value(SoilFixture.MOISTURE))
        assertTrue(METRICS in state.cards)
    }

    @Test
    fun theSoilHeroIsMoistureAndTheMetricsCardTheRest() {
        val thing = SoilFixture.capabilities().thing!!
        assertEquals(SoilFixture.MOISTURE, heroColumn(thing)?.name)
        // The battery goes to the board card, never to the metrics list.
        assertEquals(listOf(SoilFixture.TEMPERATURE), otherReadings(thing).map { it.name })
    }

    @Test
    fun theSoilBoardCardShowsItsBatteryButNoTemperature() {
        val state = soil(DetailLevel.DETAILED)
        assertEquals("battery_percent", state.power.battery?.name)
        // Soil temperature is a measurement, so the metrics card shows it, not the board card.
        assertFalse(state.presenter.boardTemperature(state))
    }

    @Test
    fun soilReadingsFormatAtTheirDecimals() {
        val thing = SoilFixture.capabilities().thing!!
        val moisture = heroColumn(thing)!!
        val reading = SoilFixture.capabilities().reading(soilSample)
        val units = UnitFormatter(UnitSystem.METRIC)
        assertEquals(0.31, reading?.value(moisture.name))
        assertEquals(NO_VALUE, formatReading(null, moisture, units))
        assertEquals(formatFixed(0.31, 2), formatReading(0.31, moisture, units), "a float without a decimals hint shows two")
    }
}

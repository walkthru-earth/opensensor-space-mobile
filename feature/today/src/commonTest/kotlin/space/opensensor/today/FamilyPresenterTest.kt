package space.opensensor.today

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
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

class FamilyPresenterTest {
    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7")
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9")
    private val soilSample = LiveSample.parse("""{"sm":0.31,"st":18.5,"pct":80}""")

    private fun state(info: DeviceInfo?, live: LiveSample?, level: DetailLevel) = TodayUiState(TodaySnapshot(info = info, live = live), level)

    @Test
    fun theRegistryPicksAirForAirMoodAndGenericForEverythingElse() {
        assertSame(AirPresenter, HeroRegistry.presenterFor(HeroRegistry.AIR_MOOD))
        assertSame(GenericPresenter, HeroRegistry.presenterFor(null))
        assertSame(GenericPresenter, HeroRegistry.presenterFor("soil-dial"))
    }

    @Test
    fun theBundledAirBoardsNameTheAirHero() {
        assertSame(AirPresenter, state(waveshare, null, DetailLevel.SIMPLE).presenter)
        assertSame(AirPresenter, state(cores3, null, DetailLevel.SIMPLE).presenter)
    }

    @Test
    fun aSoilBoardShowsSoilCards() = SoilFixture.served {
        val simple = state(SoilFixture.info, soilSample, DetailLevel.SIMPLE)
        assertSame(GenericPresenter, simple.presenter)
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK), simple.cards)
        assertEquals(
            listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK, METRICS, BOARD, STORAGE, DEVICE_INFO),
            state(SoilFixture.info, soilSample, DetailLevel.DETAILED).cards,
        )
    }

    @Test
    fun theSoilHeroIsMoistureAndTheMetricsCardTheRest() {
        val thing = SoilFixture.capabilities().thing!!
        assertEquals(SoilFixture.MOISTURE, heroColumn(thing)?.name)
        // The battery goes to the board card, never to the metrics list.
        assertEquals(listOf(SoilFixture.TEMPERATURE), otherReadings(thing).map { it.name })
    }

    @Test
    fun theSoilBoardCardShowsItsBatteryButNoTemperature() = SoilFixture.served {
        val state = state(SoilFixture.info, soilSample, DetailLevel.DETAILED)
        assertEquals("battery_percent", state.power.battery?.name)
        // Soil temperature is a measurement, so the metrics card shows it, not the board card.
        assertFalse(state.presenter.boardTemperature(state))
    }

    @Test
    fun soilReadingsFormatAtTheirDisplayStep() {
        val thing = SoilFixture.capabilities().thing!!
        val moisture = heroColumn(thing)!!
        val reading = SoilFixture.capabilities().reading(soilSample)
        val units = UnitFormatter(UnitSystem.METRIC)
        assertEquals(0.31, reading?.value(moisture.name))
        assertEquals(NO_VALUE, formatReading(null, moisture, units))
        assertEquals(formatFixed(0.3, 1), formatReading(0.31, moisture, units), "the derived profile shows one decimal")
    }
}

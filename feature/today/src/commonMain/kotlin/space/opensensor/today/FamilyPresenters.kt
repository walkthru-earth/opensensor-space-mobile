package space.opensensor.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import space.opensensor.model.Role
import space.opensensor.model.Thing
import space.opensensor.model.ThingDatastream

/**
 * How one family draws Today, picked by the app profile's `hero` through [HeroRegistry]. [Hero]
 * fills the [TodayCard.NOW] slot. [simpleCards] sit after today so far and [detailedCards] after
 * the batch estimate, so the shared cards never move. A presenter reads [TodayUiState] only, never
 * the transport, and is the same on Android and iOS.
 */
interface FamilyPresenter {
    /** Cards this family adds at every level. */
    fun simpleCards(state: TodayUiState): List<TodayCard>

    /** Cards this family adds in Detailed and Developer. */
    fun detailedCards(state: TodayUiState): List<TodayCard>

    /** True when the board card shows the LIVE temperature. */
    fun boardTemperature(state: TodayUiState): Boolean

    @Composable
    fun Hero(state: TodayUiState, modifier: Modifier)
}

/** The family presenters by app profile `hero` id. Every other id, or none, gets [GenericPresenter]. */
object HeroRegistry {
    /** The air family's peacock hero with WHO moods. */
    const val AIR_MOOD = "air-mood"

    private val presenters: Map<String, FamilyPresenter> = mapOf(AIR_MOOD to AirPresenter)

    fun presenterFor(hero: String?): FamilyPresenter = hero?.let(presenters::get) ?: GenericPresenter
}

/**
 * The air family. The peacock hero while LIVE shows the particle sensor, else the temperature
 * card. Ambient temperature, the other particle sizes and the size bins get their own cards.
 */
object AirPresenter : FamilyPresenter {
    override fun simpleCards(state: TodayUiState): List<TodayCard> =
        if (state.showsAirCards && state.capabilities.ambientTemperature) listOf(TodayCard.ENVIRONMENT) else emptyList()

    override fun detailedCards(state: TodayUiState): List<TodayCard> =
        if (state.showsAirCards) listOf(TodayCard.READINGS, TodayCard.PARTICLES) else emptyList()

    override fun boardTemperature(state: TodayUiState): Boolean = true

    @Composable
    override fun Hero(state: TodayUiState, modifier: Modifier) {
        if (state.showsAirCards) {
            MetricHeroCard(state.reading, state.snapshot.liveAtEpochMs, state.level, modifier)
        } else {
            EnvironmentCard(state.liveSupport, state.capabilities, state.reading, state.snapshot.liveAtEpochMs, hero = true, modifier = modifier)
        }
    }
}

/**
 * Any thing without a family hero. [GenericMetricHero] shows the first primary column and
 * [TodayCard.METRICS] lists the rest, all from the template and the app profile.
 */
object GenericPresenter : FamilyPresenter {
    override fun simpleCards(state: TodayUiState): List<TodayCard> = emptyList()

    override fun detailedCards(state: TodayUiState): List<TodayCard> =
        if (state.capabilities.thing?.let(::otherReadings).isNullOrEmpty()) emptyList() else listOf(TodayCard.METRICS)

    /** Only a board's own temperature. A measured one is the hero or one of the other readings. */
    override fun boardTemperature(state: TodayUiState): Boolean {
        val thing = state.capabilities.thing ?: return true
        val temperature = state.capabilities.temperature ?: return false
        return temperature.datastream.role != Role.PHYSICAL_MEASUREMENT && temperature !in otherReadings(thing)
    }

    @Composable
    override fun Hero(state: TodayUiState, modifier: Modifier) = GenericMetricHero(state, modifier)
}

/** The column the generic hero shows, the first primary column of [thing], or null when it has none. */
fun heroColumn(thing: Thing): ThingDatastream? = thing.primary.firstOrNull()

/**
 * The columns [TodayCard.METRICS] lists for [thing]. The primary columns after the hero, then every
 * other `physicalMeasurement` the profile does not hide, in template order. Only columns LIVE shows,
 * and none of the power columns the board card shows.
 */
fun otherReadings(thing: Thing): List<ThingDatastream> {
    val skip = BoardPower.of(thing).let { listOfNotNull(heroColumn(thing), it.battery, it.millivolts, it.usbMillivolts, it.state) }.map { it.name }.toSet()
    val measured = thing.datastreams.filter { it.datastream.role == Role.PHYSICAL_MEASUREMENT && it.name !in thing.profile.hidden }
    return (thing.primary + measured).distinctBy { it.name }.filter { it.name !in skip && it.datastream.liveKey in thing.live.keys }
}

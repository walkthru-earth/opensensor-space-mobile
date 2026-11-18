package space.opensensor.family.air

import space.opensensor.data.history.Metrics
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Capability
import space.opensensor.model.Derivation
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.Role
import space.opensensor.model.RowCheck
import space.opensensor.model.RowFormula
import space.opensensor.model.Thing
import space.opensensor.publicfeed.DiscoverCollection

/**
 * The air family, what it adds to the generic core through [DerivedMetricProvider]. It finds its
 * columns by `opensensor:property` ([AirColumns]), never by name, so any board that declares those
 * properties gets it with no app change. A template that stores a Plantower CF=1 PM2.5 and RH gets the
 * US EPA corrected PM2.5 ([EpaPm25Correction], #92), a corrected view of its PM2.5 that shares its
 * scale and primary flag. Nothing stored changes. Scales come from the template and the registries,
 * never from this family. A Thing gets [BATCH_ESTIMATE] and [PARTICLES] by property too.
 *
 * The EPA NowCast for short PM windows ([PmNowCast], #93) and the quality checks for suspect PM
 * readings ([QcRules], [LiveQc], #91) live here too, next to the correction.
 */
object AirFamily : DerivedMetricProvider {
    /** The board writes the unvalidated Plantower batch estimate ([AirProperty.BATCH_ESTIMATE]). */
    val BATCH_ESTIMATE = Capability("batch-estimate")

    /** A Thing whose LIVE shows a physical PM2.5 ([AirProperty.PM25]) on the PM2.5 scale, the particle cards. */
    val PARTICLES = Capability("particles")

    /** The corrected PM2.5 id, a stable tier key. */
    const val PM25_CORRECTED_ID = "pm25_epa"

    /** The `derived.method` of the corrected PM2.5. */
    const val EPA_METHOD = "us-epa-pm25-2021"

    /*
     * The bundled CoreS3 and Waveshare column names, for tests, previews and the benchmark. The family
     * itself reads columns by property ([AirColumns]).
     */
    const val PM25_CF1 = "pm25_cf1_ug_m3"
    const val PM25_ATMOSPHERIC = "pm25_atmospheric_ug_m3"
    const val RELATIVE_HUMIDITY = "relative_humidity_percent"
    const val PM1_ATMOSPHERIC = "pm1_atmospheric_ug_m3"
    const val PM10_ATMOSPHERIC = "pm10_atmospheric_ug_m3"

    /** The bundled CoreS3 count bin names, per 0.1 L, from the smallest size up, for tests. */
    val PARTICLE_BINS: List<String> = listOf("gt03", "gt05", "gt10", "gt25", "gt50", "gt100").map { "particles_${it}_per_01l" }

    override fun derive(template: BoardTemplate): List<DerivedMetric> {
        val air = AirColumns.of(template)?.takeIf { it.epa } ?: return emptyList()
        val cf1 = air.pm25Cf1 ?: return emptyList()
        val rh = air.humidity ?: return emptyList()
        val corrected = cf1.copy(
            name = PM25_CORRECTED_ID,
            fieldId = null,
            liveKey = null,
            unitLabel = null,
            derived = Derivation(listOf(cf1.name, rh.name), EPA_METHOD),
        )
        return listOf(DerivedMetric(corrected, label = "PM2.5 corrected", formula = EpaFormula, viewOf = air.pm25.name))
    }

    /**
     * The stored quality checks ([AirQc], #91) for a template that stores a PM2.5, with the PM1, PM10,
     * RH and count bins it declares. Every other template gets none.
     */
    override fun rowCheck(template: BoardTemplate): RowCheck? {
        val air = AirColumns.of(template) ?: return null
        return AirQc(air.pm1?.name, air.pm25.name, air.pm10?.name, air.humidity?.name, air.bins.map { it.name })
    }

    override fun capabilitiesOf(thing: Thing): Set<Capability> = buildSet {
        val air = thing.air ?: return@buildSet
        if (air.batchEstimate != null) add(BATCH_ESTIMATE)
        if (thing.showsPm25Live(air)) add(PARTICLES)
    }

    /**
     * Installs the air family where the generic core reads families. History's metrics and
     * capabilities ([Metrics.install], with [others] after it) and the scales Discover ships for a feed
     * without a Collection ([DiscoverCollection.installBundled]). It is the one install path of both
     * apps. Android's ThingModule and iOS's launchThingKinds call it once at launch with their Thing
     * kinds' derived metric providers as [others], before any screen reads a metric. Tests call it with
     * none.
     */
    fun install(others: List<DerivedMetricProvider> = emptyList()) {
        Metrics.install(listOf(this) + others)
        DiscoverCollection.installBundled(AirDiscover.COLLECTION)
    }

    /** The PM2.5 of [air] is a physical measurement on the WHO PM2.5 scale that LIVE shows. */
    private fun Thing.showsPm25Live(air: AirColumns): Boolean {
        val pm25 = datastream(air.pm25.name) ?: return false
        return pm25.scale?.id == WhoGuidelines.PM25.id && pm25.datastream.role == Role.PHYSICAL_MEASUREMENT && pm25.datastream.liveKey in live.keys
    }

    private object EpaFormula : RowFormula {
        override fun valueOf(primary: Double, second: Double?): Double? = EpaPm25Correction.correct(primary, second)
    }
}

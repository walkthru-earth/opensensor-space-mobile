package space.opensensor.family.air

import space.opensensor.data.history.Metrics
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Capability
import space.opensensor.model.Derivation
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.Family
import space.opensensor.model.HealthScale
import space.opensensor.model.Role
import space.opensensor.model.RowFormula
import space.opensensor.model.Thing
import space.opensensor.publicfeed.DiscoverCollection

/**
 * The air family, what it adds to the generic core through [DerivedMetricProvider]. A template that
 * stores CF=1 PM2.5 and RH gets the US EPA corrected PM2.5 ([EpaPm25Correction], #92), a corrected
 * view of the atmospheric PM2.5 that shares its scale and primary flag. Nothing stored changes. The
 * profile scale ids `pm25` and `pm10` read the WHO 2021 scales ([WhoGuidelines]). An air Thing gets
 * [BATCH_ESTIMATE] and [PARTICLES].
 *
 * A NowCast for short PM windows (#93) belongs here too, next to the correction, once it lands.
 */
object AirFamily : DerivedMetricProvider {
    /** The family id of an air profile. */
    val FAMILY = Family("air")

    /** The board writes the unvalidated Plantower batch estimate column ([AirMetrics.BATCH_ESTIMATE_COLUMN]). */
    val BATCH_ESTIMATE = Capability("batch-estimate")

    /** An air profile whose LIVE shows a physical PM2.5 on the PM2.5 scale, the particle cards. */
    val PARTICLES = Capability("particles")

    /** The profile scale id of PM2.5. */
    const val PM25_SCALE = "pm25"

    /** The profile scale id of PM10. */
    const val PM10_SCALE = "pm10"

    /** The corrected PM2.5 id, a stable tier key. */
    const val PM25_CORRECTED_ID = "pm25_epa"

    /** The `derived.method` of the corrected PM2.5. */
    const val EPA_METHOD = "us-epa-pm25-2021"

    const val PM25_CF1 = "pm25_cf1_ug_m3"
    const val PM25_ATMOSPHERIC = "pm25_atmospheric_ug_m3"
    const val RELATIVE_HUMIDITY = "relative_humidity_percent"

    override fun derive(template: BoardTemplate): List<DerivedMetric> {
        val cf1 = template.datastream(PM25_CF1) ?: return emptyList()
        if (template.datastream(RELATIVE_HUMIDITY) == null) return emptyList()
        val corrected = cf1.copy(
            name = PM25_CORRECTED_ID,
            fieldId = null,
            liveKey = null,
            unitLabel = null,
            derived = Derivation(listOf(PM25_CF1, RELATIVE_HUMIDITY), EPA_METHOD),
        )
        return listOf(DerivedMetric(corrected, label = "PM2.5 corrected", formula = EpaFormula, viewOf = PM25_ATMOSPHERIC))
    }

    override val scales: Map<String, HealthScale> = mapOf(PM25_SCALE to WhoGuidelines.PM25, PM10_SCALE to WhoGuidelines.PM10)

    override fun capabilitiesOf(thing: Thing): Set<Capability> = buildSet {
        if (thing.datastream(AirMetrics.BATCH_ESTIMATE_COLUMN) != null) add(BATCH_ESTIMATE)
        if (thing.profile.family == FAMILY && thing.showsPm25Live()) add(PARTICLES)
    }

    /**
     * Installs the air family where the generic core reads families. History's metrics, scales and
     * capabilities ([Metrics.install], with [others] after it) and the scales Discover ships for a feed
     * without a Collection ([DiscoverCollection.installBundled]). Each app entry point calls it once at
     * launch, before any screen reads a metric.
     */
    fun install(others: List<DerivedMetricProvider> = emptyList()) {
        Metrics.install(listOf(this) + others)
        DiscoverCollection.installBundled(AirDiscover.COLLECTION)
    }

    /** A physical PM2.5 measurement on the PM2.5 scale that LIVE shows. */
    private fun Thing.showsPm25Live(): Boolean = datastreams.any {
        it.scaleId == PM25_SCALE && it.datastream.role == Role.PHYSICAL_MEASUREMENT && it.datastream.liveKey in live.keys
    }

    private object EpaFormula : RowFormula {
        override fun valueOf(primary: Double, second: Double?): Double? = EpaPm25Correction.correct(primary, second)
    }
}

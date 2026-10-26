package space.opensensor.data.history

import space.opensensor.airquality.EpaPm25Correction
import space.opensensor.airquality.HealthScale
import space.opensensor.airquality.Pm25Basis
import space.opensensor.airquality.WhoGuidelines
import space.opensensor.airquality.WhoStep
import space.opensensor.model.Aggregate
import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Datastream
import space.opensensor.model.LocalizedText
import space.opensensor.model.PlausibleRange
import space.opensensor.model.ValidityGate

/**
 * One quantity History keeps in its tiers and charts, built from a board template datastream and the
 * app profile ([Metrics.of]). The gate, settle time, aggregate, unit and plausible range are the
 * template's. The label, display unit and health scale are the profile's. Nothing here names a board.
 *
 * [id] is the column name, the tier key and the picker key on both platforms. The one exception is a
 * metric the phone derives, such as [Metrics.PM25_CORRECTED], which has its own id.
 */
data class MetricSpec(
    val id: String,
    /** The stored column the values come from. */
    val column: String,
    /** English label, from the profile, else the column name. */
    val label: String,
    /** Unit text the screens show, from the profile, else the template unit. */
    val unit: String,
    /** The template's UCUM unit of [column]. Files whose template disagrees are not mixed in (rule 9). */
    val sourceUnit: String? = null,
    /** The template's `opensensor:valid_when`, or null when every non-null value counts. */
    val gate: ValidityGate? = null,
    /** The template's `opensensor:settle_s`. Rows that early after power on count but are flagged as settling. Not a gate. */
    val settleSeconds: Double = 0.0,
    /** How a tier bucket rolls rows up. A missing aggregate is a mean, an unknown one is a point. */
    val aggregate: Aggregate = Aggregate.MEAN,
    /** The template's `opensensor:plausible_range`, the axis bounds of a neutral chart. */
    val plausibleRange: PlausibleRange? = null,
    /**
     * The published health scale (WHO 2021 for PM2.5 and PM10) that colours and names a value, or
     * null for a metric shown in a neutral colour. Its property id and unit must match [property].
     */
    val healthScale: HealthScale? = null,
    /** The quantity this metric measures, the health scale key. Two views of one quantity share it. */
    val property: String = id,
    /** Raw or corrected, for the PM2.5 metrics, so every chart and card can say which it shows. */
    val basis: Pm25Basis? = null,
    /**
     * The RH column the EPA humidity correction reads, or null for a metric shown as stored. When set,
     * each row's value is [EpaPm25Correction.correct] of [column] (CF=1 PM2.5) and this column, computed
     * per row before any rollup, and a row without a valid RH has no value.
     */
    val humidityColumn: String? = null,
    /** True for a column of the profile's `primary` list, the metrics Simple offers. */
    val primary: Boolean = false,
    /** Labels by language from the profile, or null when it has none. */
    val labels: LocalizedText? = null,
) {
    init {
        require(healthScale == null || (healthScale.propertyId == property && healthScale.unit == unit)) {
            "a health scale reads only its own property ($property, $unit)"
        }
        require(humidityColumn == null || basis == Pm25Basis.CORRECTED) { "a humidity-corrected metric is labelled corrected" }
    }

    /** The value a row shows from its stored [raw] value and its [rh], or null when it has none. */
    fun valueOf(raw: Double, rh: Double?): Double? = when {
        !raw.isFinite() -> null
        humidityColumn == null -> raw
        else -> EpaPm25Correction.correct(raw, rh)
    }

    /** The stored columns a row's value reads. */
    val sourceColumns: List<String> get() = listOfNotNull(column, humidityColumn)

    /** True when the tiers keep a min and max worth drawing as an envelope, which only a mean has. */
    val envelope: Boolean get() = aggregate == Aggregate.MEAN

    val banded: Boolean get() = healthScale != null

    /** The label in [language], else the English one. */
    fun label(language: String): String = labels?.get(language) ?: label

    /** The WHO step of [value], or null for a neutral metric or a non-finite value. */
    fun stepOf(value: Double): WhoStep? = healthScale?.stepOf(value)

    fun moodOf(value: Double): AirMood? = stepOf(value)?.let { AirMood.of(it) }

    /**
     * This metric as [template] defines it, with that template's gate and settle time, or null when
     * the template lacks a source column, stores it in another unit or rolls it up another way. A file
     * of another template then gives this metric no values, so units never mix in one bucket.
     */
    fun inTemplate(template: BoardTemplate): MetricSpec? {
        val ds = template.datastream(column) ?: return null
        if (ds.unit != sourceUnit || Metrics.rollupOf(ds) != aggregate) return null
        if (humidityColumn != null && template.datastream(humidityColumn) == null) return null
        return if (ds.validWhen == gate && ds.settleSeconds == settleSeconds) this else copy(gate = ds.validWhen, settleSeconds = ds.settleSeconds)
    }
}

/**
 * Builds History's metrics from board templates and app profiles. The bundled templates give the
 * [catalog] the screens pick from. The tiers and readers take any list, so a template the app does
 * not bundle aggregates the same way once something resolves it.
 */
object Metrics {
    /** Health scale ids an app profile may name, with the axis floor that keeps a normal day readable. */
    private val SCALES: Map<String, Pair<HealthScale, Float>> = mapOf(
        "pm25" to (WhoGuidelines.PM25 to 37.5f),
        "pm10" to (WhoGuidelines.PM10 to 75f),
    )

    /** The derived metric's id, a stable tier key. */
    const val PM25_CORRECTED_ID = "pm25_epa"
    private const val PM25_CF1 = "pm25_cf1_ug_m3"
    private const val PM25_ATMOSPHERIC = "pm25_atmospheric_ug_m3"
    private const val HUMIDITY_COLUMN = "relative_humidity_percent"

    /** The axis floor of [metric]'s health scale, so the guideline stays in view, or null for a neutral metric. */
    fun axisFloorOf(metric: MetricSpec): Float? = metric.healthScale?.let { scale -> SCALES.values.firstOrNull { it.first == scale }?.second }

    /** The aggregate the tiers apply to [ds]. A missing aggregate is a mean, an unknown one a point (rule 5). */
    fun rollupOf(ds: Datastream): Aggregate = if (ds.aggregate == null) Aggregate.MEAN else ds.rollup

    /** The metric for [ds] under [profile]. */
    fun of(ds: Datastream, profile: AppProfile): MetricSpec {
        val scale = profile.scales[ds.name]?.let { SCALES[it] }?.first
        val labels = profile.labels[ds.name]
        return MetricSpec(
            id = ds.name,
            column = ds.name,
            label = labels?.get(LocalizedText.ENGLISH) ?: ds.name,
            unit = profile.displayUnit[ds.name] ?: ds.unit.orEmpty(),
            sourceUnit = ds.unit,
            gate = ds.validWhen,
            settleSeconds = ds.settleSeconds,
            aggregate = rollupOf(ds),
            plausibleRange = ds.plausibleRange,
            healthScale = scale,
            property = scale?.propertyId ?: ds.name,
            basis = if (ds.name == PM25_ATMOSPHERIC) Pm25Basis.RAW else null,
            primary = ds.name in profile.primary && ds.name !in profile.hidden,
            labels = labels,
        )
    }

    /**
     * The metrics of [template] under [profile]. The profile's primary columns come first in its order,
     * then every other measured (`physicalMeasurement` or `modelResult`) column the profile does not
     * hide, in template order. A board-derived column counts when the file stores it, and a file
     * without it gives it no values. When the template stores CF=1 PM2.5 and RH, the EPA corrected PM2.5
     * comes first, the phone derives it (#92).
     */
    fun of(template: BoardTemplate, profile: AppProfile = AppProfile.select(template, BundledCatalog.profile(template.dict))): List<MetricSpec> {
        val columns = LinkedHashSet<String>()
        profile.primary.filterTo(columns) { it !in profile.hidden && template.datastream(it) != null }
        template.datastreams.filter { it.role?.isMeasurement == true && it.name !in profile.hidden }.mapTo(columns) { it.name }
        val stored = columns.map { of(template.datastream(it)!!, profile) }
        return listOfNotNull(corrected(template, profile)) + stored
    }

    /** The EPA corrected PM2.5 of [template], or null when it lacks CF=1 PM2.5 or RH. */
    private fun corrected(template: BoardTemplate, profile: AppProfile): MetricSpec? {
        val cf1 = template.datastream(PM25_CF1) ?: return null
        if (template.datastream(HUMIDITY_COLUMN) == null) return null
        val shown = template.datastream(PM25_ATMOSPHERIC)?.let { of(it, profile) }
        return MetricSpec(
            id = PM25_CORRECTED_ID,
            column = PM25_CF1,
            label = "PM2.5 corrected",
            unit = shown?.unit ?: cf1.unit.orEmpty(),
            sourceUnit = cf1.unit,
            gate = cf1.validWhen,
            settleSeconds = cf1.settleSeconds,
            aggregate = rollupOf(cf1),
            plausibleRange = cf1.plausibleRange,
            healthScale = shown?.healthScale,
            property = shown?.property ?: PM25_CORRECTED_ID,
            basis = Pm25Basis.CORRECTED,
            humidityColumn = HUMIDITY_COLUMN,
            primary = shown?.primary ?: false,
        )
    }

    /**
     * Every metric of the bundled templates, each id once, in the order the first profile lists it.
     * Where two boards describe one column, the first keeps its unit and aggregate and the plausible
     * ranges join. A board that stores it otherwise gives it no values ([MetricSpec.inTemplate]).
     */
    val catalog: List<MetricSpec> = merge(BundledCatalog.templates.sortedBy { it.dict }.map { of(it) })

    internal fun merge(lists: List<List<MetricSpec>>): List<MetricSpec> {
        val out = LinkedHashMap<String, MetricSpec>()
        for (spec in lists.flatten()) {
            val known = out[spec.id]
            out[spec.id] = when {
                known == null -> spec
                else -> known.copy(
                    plausibleRange = join(known.plausibleRange, spec.plausibleRange),
                    primary = known.primary || spec.primary,
                    label = if (known.labels == null && spec.labels != null) spec.label else known.label,
                    labels = known.labels ?: spec.labels,
                )
            }
        }
        return out.values.toList()
    }

    private fun join(a: PlausibleRange?, b: PlausibleRange?): PlausibleRange? =
        if (a == null || b == null) a ?: b else PlausibleRange(minOf(a.min, b.min), maxOf(a.max, b.max))

    /** Every metric the tiers keep, the [catalog]. Order is picker order. */
    val all: List<MetricSpec> get() = catalog

    fun byId(id: String): MetricSpec? = catalog.firstOrNull { it.id == id }

    private fun named(id: String): MetricSpec = requireNotNull(byId(id)) { "no bundled template has $id" }

    /** PM2.5 with the US EPA humidity correction, from the stored CF=1 PM2.5 and RH, row by row. Nothing stored changes. */
    val PM25_CORRECTED: MetricSpec = named(PM25_CORRECTED_ID)

    /** The sensor's own atmospheric PM2.5, as stored. */
    val PM25: MetricSpec = named(PM25_ATMOSPHERIC)
    val PM10: MetricSpec = named("pm10_atmospheric_ug_m3")
    val PM1: MetricSpec = named("pm1_atmospheric_ug_m3")

    /** Optional Plantower batch equation, gated separately and never used as the primary PM2.5 reading. */
    val PM25_BATCH_ESTIMATE: MetricSpec = named("pm25_batch_candidate_ug_m3")
    val PARTICLES_GT03: MetricSpec = named("particles_gt03_per_01l")
    val PARTICLES_GT25: MetricSpec = named("particles_gt25_per_01l")
    val BOARD_TEMP: MetricSpec = named("imu_temperature_c")

    /** Null on CoreS3 (SHT20 address collision), written by the Waveshare PMS5003T. A null RH leaves PM2.5 raw. */
    val AMBIENT_TEMP: MetricSpec = named("ambient_temperature_c")
    val HUMIDITY: MetricSpec = named(HUMIDITY_COLUMN)
    val BATTERY: MetricSpec = named("battery_percent")

    /** The PM2.5 views, corrected first, the order every PM2.5 card picks from. */
    val pm25Views: List<MetricSpec> get() = listOf(PM25_CORRECTED, PM25)

    /**
     * The metric History opens on for [metrics], the first primary column, or its corrected view when
     * it is PM2.5 and one exists (#92). Null when nothing is primary.
     */
    fun defaultOf(metrics: List<MetricSpec>): MetricSpec? {
        val first = metrics.firstOrNull { it.primary && it.humidityColumn == null } ?: return metrics.firstOrNull()
        return metrics.firstOrNull { it.humidityColumn != null && it.property == first.property && first.basis == Pm25Basis.RAW } ?: first
    }

    /** The metric History opens on, [defaultOf] the [catalog]. */
    val default: MetricSpec get() = defaultOf(catalog) ?: PM25_CORRECTED
}

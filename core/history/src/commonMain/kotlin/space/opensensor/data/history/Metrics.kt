package space.opensensor.data.history

import space.opensensor.model.Aggregate
import space.opensensor.model.AppProfile
import space.opensensor.model.Basis
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Datastream
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.HealthScale
import space.opensensor.model.LocalizedText
import space.opensensor.model.PlausibleRange
import space.opensensor.model.RowFormula
import space.opensensor.model.ValidityGate
import kotlin.concurrent.Volatile

/**
 * One quantity History keeps in its tiers and charts, built from a board template datastream and the
 * app profile ([Metrics.of]). The gate, settle time, aggregate, unit and plausible range are the
 * template's. The label, display unit and health scale are the profile's. Nothing here names a board.
 *
 * [id] is the column name, the tier key and the picker key on both platforms. The one exception is a
 * metric the phone derives ([formula] set, from a [DerivedMetricProvider]), which has its own id.
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
     * The published health scale a family names ([DerivedMetricProvider.scales]) that colours and
     * names a value, or null for a metric shown in a neutral colour. Its property id and unit must match [property].
     */
    val healthScale: HealthScale? = null,
    /** The quantity this metric measures, the health scale key. Two views of one quantity share it. */
    val property: String = id,
    /**
     * Raw or corrected, for a stored column with a derived corrected view and for that view, so every
     * chart and card can say which it shows. Null for every other metric. It says nothing about the
     * family, the label comes from [property].
     */
    val basis: Basis? = null,
    /**
     * The per row formula of a derived metric, or null for a metric shown as stored. When set, each
     * row's value is the formula of [column] and [auxColumn], computed per row before any rollup, and a
     * row the formula gives no value (no valid RH, say) has none.
     */
    val formula: RowFormula? = null,
    /** The second stored column [formula] reads, or null when it reads only [column]. */
    val auxColumn: String? = null,
    /** True for a column of the profile's `primary` list, the metrics Simple offers. */
    val primary: Boolean = false,
    /** Labels by language from the profile, or null when it has none. */
    val labels: LocalizedText? = null,
) {
    init {
        require(healthScale == null || (healthScale.propertyId == property && healthScale.unit == unit)) {
            "a health scale reads only its own property ($property, $unit)"
        }
        require(auxColumn == null || formula != null) { "only a derived metric reads a second column" }
    }

    /** True for a metric the phone derives per row, see [formula]. */
    val derived: Boolean get() = formula != null

    /** The value a row shows from its stored [raw] value and its [aux] value, or null when it has none. */
    fun valueOf(raw: Double, aux: Double?): Double? = when {
        !raw.isFinite() -> null
        formula == null -> raw
        else -> formula.valueOf(raw, aux)
    }

    /** The stored columns a row's value reads. */
    val sourceColumns: List<String> get() = listOfNotNull(column, auxColumn)

    /** True when the tiers keep a min and max worth drawing as an envelope, which only a mean has. */
    val envelope: Boolean get() = aggregate == Aggregate.MEAN

    val banded: Boolean get() = healthScale != null

    /** The label in [language], else the English one. */
    fun label(language: String): String = labels?.get(language) ?: label

    /**
     * This metric as [template] defines it, with that template's gate and settle time, or null when
     * the template lacks a source column, stores it in another unit or rolls it up another way. A file
     * of another template then gives this metric no values, so units never mix in one bucket.
     */
    fun inTemplate(template: BoardTemplate): MetricSpec? {
        val ds = template.datastream(column) ?: return null
        if (ds.unit != sourceUnit || Metrics.rollupOf(ds) != aggregate) return null
        if (auxColumn != null && template.datastream(auxColumn) == null) return null
        return if (ds.validWhen == gate && ds.settleSeconds == settleSeconds) this else copy(gate = ds.validWhen, settleSeconds = ds.settleSeconds)
    }
}

/**
 * Builds History's metrics from board templates and app profiles. The bundled templates give the
 * [catalog] the screens pick from. The tiers and readers take any list, so a template the app does
 * not bundle aggregates the same way once something resolves it. Nothing here names a column. Family
 * knowledge comes from the profile's scale ids and from the [providers] of derived metrics.
 */
object Metrics {
    /** The installed providers and the catalog built from them on first read. */
    private class Installed(val providers: List<DerivedMetricProvider>) {
        val catalog: List<MetricSpec> by lazy { catalogOf(BundledCatalog.templates, providers) }
    }

    /** No family until an app entry point installs its own, so every column stays neutral. */
    @Volatile
    private var installed = Installed(emptyList())

    /** The derived metric providers the catalog applies, in order. */
    val providers: List<DerivedMetricProvider> get() = installed.providers

    /**
     * Makes [providers] the ones the [catalog] applies, the app's families and then the derived
     * metrics of its Thing kinds. A family's own install calls it once at each app's launch, before
     * any screen reads a metric, since a family's named specs keep what they first read.
     */
    fun install(providers: List<DerivedMetricProvider>) {
        installed = Installed(providers.distinct())
    }

    /** The axis floor of [metric]'s health scale, so the guideline stays in view, or null for a neutral metric. */
    fun axisFloorOf(metric: MetricSpec): Float? = metric.healthScale?.axisFloor?.toFloat()

    /** The aggregate the tiers apply to [ds]. A missing aggregate is a mean, an unknown one a point (rule 5). */
    fun rollupOf(ds: Datastream): Aggregate = if (ds.aggregate == null) Aggregate.MEAN else ds.rollup

    /** Every health scale the [providers] publish, by scale id. The first provider that names an id keeps it. */
    fun scalesOf(providers: List<DerivedMetricProvider> = this.providers): Map<String, HealthScale> =
        providers.reversed().fold(emptyMap()) { acc, p -> acc + p.scales }

    /**
     * The metric for [ds] under [profile]. [basis] marks a column with a derived corrected view. The
     * profile's scale id picks one of [scales], and an id no family publishes leaves the column neutral.
     */
    fun of(ds: Datastream, profile: AppProfile, basis: Basis? = null, scales: Map<String, HealthScale> = scalesOf()): MetricSpec {
        val scale = profile.scales[ds.name]?.let(scales::get)
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
            basis = basis,
            primary = ds.name in profile.primary && ds.name !in profile.hidden,
            labels = labels,
        )
    }

    /**
     * The metrics of [template] under [profile]. The [providers]' derived metrics come first, then the
     * profile's primary columns in its order, then every other measured (`physicalMeasurement` or
     * `modelResult`) column the profile does not hide, in template order. A board-derived column counts
     * when the file stores it, and a file without it gives it no values. A template with none of a
     * provider's source columns simply gets none of its metrics. Ids are unique, a derived id that
     * repeats a stored column or another derived id is an error.
     */
    fun of(
        template: BoardTemplate,
        profile: AppProfile = AppProfile.select(template, BundledCatalog.profile(template.dict)),
        providers: List<DerivedMetricProvider> = this.providers,
    ): List<MetricSpec> {
        val derived = providers.flatMap { it.derive(template) }
        val scales = scalesOf(providers)
        val views = derived.mapNotNullTo(HashSet()) { it.viewOf }
        val columns = LinkedHashSet<String>()
        profile.primary.filterTo(columns) { it !in profile.hidden && template.datastream(it) != null }
        template.datastreams.filter { it.role?.isMeasurement == true && it.name !in profile.hidden }.mapTo(columns) { it.name }
        val stored = columns.mapNotNull { name -> template.datastream(name)?.let { of(it, profile, if (name in views) Basis.RAW else null, scales) } }
        val metrics = derived.map { of(it, template, profile, scales) } + stored
        val repeated = metrics.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "metric ids repeat on ${template.dict}: ${repeated.joinToString()}" }
        return metrics
    }

    /** [metric] on [template], sharing the profile unit, scale and primary flag of the column it is a view of. */
    private fun of(metric: DerivedMetric, template: BoardTemplate, profile: AppProfile, scales: Map<String, HealthScale>): MetricSpec {
        val ds = metric.datastream
        val from = ds.sourceColumns
        val shown = metric.viewOf?.let(template::datastream)?.let { of(it, profile, scales = scales) }
        return MetricSpec(
            id = ds.name,
            column = from.first(),
            label = metric.label,
            unit = shown?.unit ?: ds.unit.orEmpty(),
            sourceUnit = ds.unit,
            gate = ds.validWhen,
            settleSeconds = ds.settleSeconds,
            aggregate = rollupOf(ds),
            plausibleRange = ds.plausibleRange,
            healthScale = shown?.healthScale,
            property = shown?.property ?: ds.name,
            basis = if (metric.viewOf != null) Basis.CORRECTED else null,
            formula = metric.formula,
            auxColumn = from.getOrNull(1),
            primary = shown?.primary ?: false,
        )
    }

    /**
     * Every metric of the bundled templates, each id once, in the order the first profile lists it.
     * Where two boards describe one column, the first keeps its unit and aggregate and the plausible
     * ranges join. A board that stores it otherwise gives it no values ([MetricSpec.inTemplate]).
     */
    val catalog: List<MetricSpec> get() = installed.catalog

    /** The catalog of [templates] under their bundled or derived profiles and [providers], see [catalog]. */
    fun catalogOf(templates: List<BoardTemplate>, providers: List<DerivedMetricProvider> = this.providers): List<MetricSpec> =
        merge(templates.sortedBy { it.dict }.map { of(it, providers = providers) })

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

    /**
     * The metric History opens on for [metrics], the first primary stored column, or its corrected
     * view when one exists (#92). The first metric when nothing is primary, null for an empty list.
     */
    fun defaultOf(metrics: List<MetricSpec>): MetricSpec? {
        val first = metrics.firstOrNull { it.primary && !it.derived } ?: return metrics.firstOrNull()
        return metrics.firstOrNull { it.derived && it.property == first.property && first.basis == Basis.RAW } ?: first
    }

    /** The metric History opens on, [defaultOf] the [catalog]. */
    val default: MetricSpec get() = checkNotNull(defaultOf(catalog)) { "the bundled templates measure nothing" }
}

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
import space.opensensor.model.Role
import space.opensensor.model.RowFormula
import space.opensensor.model.ValidityGate
import space.opensensor.units.UcumDisplay
import kotlin.concurrent.Volatile

/**
 * One quantity History keeps in its tiers and charts, built from a board template datastream and the
 * app profile ([Metrics.of]). The gate, settle time, aggregate, unit and plausible range are the
 * template's. The label, decimals, display unit and health scale are the profile's, which reads them
 * from the template hints, the Collection and the registries. Nothing here names a board.
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
     * The published scale that colours and names a value ([AppProfile.scales]), or null for a metric
     * shown in a neutral colour. Its unit must match [unit].
     */
    val healthScale: HealthScale? = null,
    /** The quantity this metric measures, its `opensensor:property` URI, else [id]. Two views of one quantity share it. */
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
    /** True for the profile's primary column and its corrected view, the metric Simple offers. */
    val primary: Boolean = false,
    /** Labels by language from the profile, or null when it has none. */
    val labels: LocalizedText? = null,
    /** The decimals a value shows with, from the profile ([AppProfile.decimalsOf]). */
    val decimals: Int = 2,
    /**
     * True for an `auxiliaryInformation` column, such as a board temperature or a battery, and its
     * derived views. Pickers list it after the measurements, and Simple never offers it.
     */
    val auxiliary: Boolean = false,
) {
    init {
        require(healthScale == null || healthScale.unit == unit) { "a health scale reads only its own unit ($unit)" }
        require(decimals in 0..Datastream.MAX_DECIMALS) { "decimals out of range" }
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

    /**
     * True when stored row checks leave flagged rows out of this metric's clean values (#91), a metric
     * with a health scale rolled up as a mean, so Simple judges its step on the rows that passed.
     */
    val cleanable: Boolean get() = banded && aggregate == Aggregate.MEAN

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
 * Builds History's metrics from board templates and app profiles. [MetricCatalog] holds what the
 * screens pick from, one Thing's metrics and the union of every known template. The tiers and readers
 * take any list, so a template the app does not bundle aggregates the same way once something resolves
 * it. Nothing here names a column. Labels, decimals and scales come from the profile, and family
 * knowledge from the [providers] of derived metrics.
 */
object Metrics {
    /** No family until an app entry point installs its own, so every column stays neutral. */
    @Volatile
    private var installed: List<DerivedMetricProvider> = emptyList()

    /** The derived metric providers every catalog applies, in order. */
    val providers: List<DerivedMetricProvider> get() = installed

    /**
     * Makes [providers] the ones every catalog applies, the app's families and then the derived
     * metrics of its Thing kinds, and builds [MetricCatalog.shared] again under them. A family's own
     * install calls it once at each app's launch, before any screen reads a metric, since a family's
     * named specs keep what they first read.
     */
    fun install(providers: List<DerivedMetricProvider>) {
        installed = providers.distinct()
        MetricCatalog.shared.rebuild()
    }

    /** The axis floor of [metric]'s health scale, so the guideline stays in view, or null for a neutral metric. */
    fun axisFloorOf(metric: MetricSpec): Float? = metric.healthScale?.axisFloor?.toFloat()

    /** The aggregate the tiers apply to [ds]. A missing aggregate is a mean, an unknown one a point (rule 5). */
    fun rollupOf(ds: Datastream): Aggregate = if (ds.aggregate == null) Aggregate.MEAN else ds.rollup

    /**
     * The metric for [ds] under [profile]. [basis] marks a column with a derived corrected view. The
     * profile's scale for the column becomes its [HealthScale], and a column without one stays neutral.
     */
    fun of(ds: Datastream, profile: AppProfile, basis: Basis? = null): MetricSpec {
        val labels = profile.labelOf(ds)
        val unit = profile.displayUnitOf(ds).orEmpty()
        return MetricSpec(
            id = ds.name,
            column = ds.name,
            label = labels.get(LocalizedText.ENGLISH),
            unit = unit,
            sourceUnit = ds.unit,
            gate = ds.validWhen,
            settleSeconds = ds.settleSeconds,
            aggregate = rollupOf(ds),
            plausibleRange = ds.plausibleRange,
            healthScale = profile.scales[ds.name]?.let { HealthScale.of(it, unit) },
            property = ds.property?.id ?: ds.name,
            basis = basis,
            primary = ds.name == profile.primary,
            labels = labels,
            decimals = profile.decimalsOf(ds),
            auxiliary = ds.role == Role.AUXILIARY_INFORMATION,
        )
    }

    /**
     * The metrics of [template] under [profile]. The [providers]' derived metrics come first, then the
     * profile's primary column, then every other measured (`physicalMeasurement` or
     * `modelResult`) column the profile does not hide, in template order, then every
     * `auxiliaryInformation` column that [charts]. A board-derived column counts
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
        val views = derived.mapNotNullTo(HashSet()) { it.viewOf }
        val columns = LinkedHashSet<String>()
        profile.primary?.let(columns::add)
        val shown = template.datastreams.filter { it.name !in profile.hidden }
        shown.filter { it.role?.isMeasurement == true }.mapTo(columns) { it.name }
        shown.filter { it.role == Role.AUXILIARY_INFORMATION && charts(it) }.mapTo(columns) { it.name }
        val stored = columns.mapNotNull { name -> template.datastream(name)?.let { of(it, profile, if (name in views) Basis.RAW else null) } }
        val metrics = derived.map { of(it, template, profile) } + stored
        val repeated = metrics.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "metric ids repeat on ${template.dict}: ${repeated.joinToString()}" }
        return metrics
    }

    /** [metric] on [template], sharing the profile unit, scale, decimals and primary flag of the column it is a view of. */
    private fun of(metric: DerivedMetric, template: BoardTemplate, profile: AppProfile): MetricSpec {
        val ds = metric.datastream
        val from = ds.sourceColumns
        val shown = metric.viewOf?.let(template::datastream)?.let { of(it, profile) }
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
            decimals = shown?.decimals ?: profile.decimalsOf(ds),
            auxiliary = shown?.auxiliary ?: false,
        )
    }

    /**
     * True for a numeric column whose unit shows a symbol, the auxiliary columns History and Files
     * chart. A code that shows no unit, such as `{code}`, `{boolean}`, `{count}` or `1`, is a state
     * or a tally, not a quantity.
     */
    fun charts(ds: Datastream): Boolean = ds.isNumeric && UcumDisplay.of(ds.unit.orEmpty()).isNotEmpty()

    /**
     * Every metric of [templates] under their bundled or derived profiles and [providers], each id once,
     * in the order of [templates] and then of each template's metrics, the auxiliary ones last. Where
     * two boards describe one column, the first keeps its unit and aggregate and the plausible ranges
     * join. A board that stores it otherwise gives it no values ([MetricSpec.inTemplate]).
     */
    fun catalogOf(templates: List<BoardTemplate>, providers: List<DerivedMetricProvider> = this.providers): List<MetricSpec> =
        merge(templates.map { of(it, providers = providers) }).sortedBy { it.auxiliary }

    /**
     * [metrics] in the stable order a picker lists them for a Thing of [template]. The metrics
     * [template] defines come first, in its own order, then the rest in the order of [metrics], and the
     * auxiliary ones last. A null [template] keeps the order of [metrics], the auxiliary ones last.
     */
    fun pickerOrder(metrics: List<MetricSpec>, template: BoardTemplate?): List<MetricSpec> {
        val own = template?.let { of(it) }.orEmpty().withIndex().associate { (i, m) -> m.id to i }
        return metrics.sortedWith(compareBy<MetricSpec> { it.auxiliary }.thenBy { own[it.id] ?: Int.MAX_VALUE })
    }

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

    /**
     * The metric History opens on for [metrics], the first primary stored column, or its corrected
     * view when one exists (#92). The first metric when nothing is primary, null for an empty list.
     */
    fun defaultOf(metrics: List<MetricSpec>): MetricSpec? {
        val first = metrics.firstOrNull { it.primary && !it.derived } ?: return metrics.firstOrNull()
        return metrics.firstOrNull { it.derived && it.property == first.property && first.basis == Basis.RAW } ?: first
    }

    /**
     * [metrics] without each raw column whose corrected view is also in [metrics], the two sharing one
     * property. Simple shows the corrected value only, and raw only where no view exists.
     */
    fun withoutViewedRaw(metrics: List<MetricSpec>): List<MetricSpec> {
        val viewed = metrics.filter { it.basis == Basis.CORRECTED }.mapTo(HashSet()) { it.property }
        return metrics.filterNot { it.basis == Basis.RAW && it.property in viewed }
    }
}

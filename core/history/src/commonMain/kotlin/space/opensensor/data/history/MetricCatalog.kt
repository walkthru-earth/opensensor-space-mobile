package space.opensensor.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.Thing

/**
 * The metrics the app knows. [of] gives one Thing's metrics from its resolved template, the rule LIVE
 * and History share. [union] is every metric of the bundled templates and of each template [adopt]ed
 * since, for the screens and tiers that span Things (Stations, Files, the tiers). A served or href
 * template joins it through [ServedTemplates.add]. It only grows, so a metric never leaves while a file
 * of its template is indexed. Where two templates describe one column, the first keeps its unit and
 * aggregate ([Metrics.catalogOf]).
 */
class MetricCatalog(bundled: List<BoardTemplate> = BundledCatalog.templates) {
    private val templates = MutableStateFlow(bundled)
    private val _union = MutableStateFlow(Metrics.catalogOf(bundled))

    /** Every metric of the templates known so far, each id once, in picker order. */
    val union: StateFlow<List<MetricSpec>> = _union.asStateFlow()

    /** Adds [template]'s metrics to [union]. A template already held changes nothing. */
    fun adopt(template: BoardTemplate) {
        val held = templates.value
        if (held.any { it.key == template.key }) return
        templates.value = held + template
        rebuild()
    }

    /** Builds [union] again under the installed derived metric providers ([Metrics.install]). */
    fun rebuild() {
        _union.value = Metrics.catalogOf(templates.value)
    }

    /** The [union] metric of [id], or null when no known template has it. */
    fun byId(id: String): MetricSpec? = union.value.firstOrNull { it.id == id }

    /**
     * [spec] as the tiers hold it for a thing of [template], the [union] metric of its id bound to that
     * template ([MetricSpec.inTemplate]), the spec the tiers aggregate each file of that template with.
     * Null when [template] stores the column in another unit or rolls it up another way, since its files
     * then add no buckets under that id. [spec] itself when the union has no metric of its id, and the
     * tiers then hold no buckets for it, so a reader gets an empty series rather than a wrong one.
     */
    fun tiered(spec: MetricSpec, template: BoardTemplate): MetricSpec? {
        val kept = byId(spec.id) ?: return spec
        return kept.inTemplate(template)
    }

    companion object {
        /** The app's one catalog, shared by Android and iOS, grown by [ServedTemplates.shared]. */
        val shared = MetricCatalog()

        /** The metrics of [thing], from its template and profile, with the installed derived metrics. */
        fun of(thing: Thing): List<MetricSpec> = Metrics.of(thing.template, thing.profile)
    }
}

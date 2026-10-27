package space.opensensor.thing

import space.opensensor.model.DerivedMetricProvider

/**
 * The Thing kinds one app supports, a plain list built once at each app entry point (Hilt on Android,
 * `launchThingKinds` on iOS). Kotlin/Native has no `ServiceLoader` and initialises top level
 * properties lazily, so a kind that registered itself would leave this list empty on iOS.
 */
class ThingKinds(val kinds: List<ThingKind>) {
    init {
        require(kinds.map { it.id }.toSet().size == kinds.size) { "kind ids repeat: ${kinds.map { it.id }}" }
    }

    val size: Int get() = kinds.size

    /** The kind with [id], or null when this app has none. */
    operator fun get(id: KindId): ThingKind? = kinds.firstOrNull { it.id == id }

    /** The first kind's reading of [evidence], in list order, or null when no kind recognizes it. */
    fun recognize(evidence: Evidence): Recognition? = kinds.firstNotNullOfOrNull { it.recognize(evidence) }

    /**
     * Every kind's derived metric providers, each once, in list order. Each app hands them to its
     * sensor family's install at launch, which puts them after the family in `Metrics.install`.
     */
    val metricProviders: List<DerivedMetricProvider> get() = kinds.flatMap { it.derivedMetrics }.distinct()
}

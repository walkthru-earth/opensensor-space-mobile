package space.opensensor.thing

import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.sync.ThingSource
import space.opensensor.model.Capability
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.publicfeed.PublicFeed
import kotlin.jvm.JvmInline

/** A kind of Thing, such as `opensensor-board`. Kinds are versioned by this id plus [ThingKind.contractVersion]. */
@JvmInline
value class KindId(val id: String) {
    init {
        require(id.isNotBlank()) { "a kind id is not blank" }
    }

    override fun toString(): String = id
}

/**
 * One kind of Thing the app supports, an opensensor board today, later the phone or a public feed
 * station. A kind says how it is recognized, where its data comes from and whether that data may ever
 * be published. Kinds never register themselves. Each app entry point lists them in [ThingKinds].
 */
interface ThingKind {
    val id: KindId

    /** The version of this kind's contract, raised when its source or evidence changes meaning. */
    val contractVersion: Int

    /** This kind's reading of [evidence], or null when the evidence is not one of its Things. */
    fun recognize(evidence: Evidence): Recognition?

    /** Where this kind's data comes from, and through [DataSource.Recording.publish] whether it may be published. */
    val source: DataSource

    /** The phone derived metrics of the families this kind measures, which History adds to its catalog. */
    val derivedMetrics: List<DerivedMetricProvider>
}

/**
 * The `thing_source` row of [thing] recorded by this kind, at its [ThingKind.contractVersion], with
 * the kind's own settings in [configJson] (#223).
 */
fun ThingKind.sourceFor(thing: String, configJson: String = ThingSource.EMPTY_CONFIG): ThingSource =
    ThingSource(thing, id.id, contractVersion, configJson)

/** What the phone saw of a Thing, which [ThingKind.recognize] reads. */
sealed interface Evidence {
    /** A Bluetooth advertisement listing [serviceUuids]. */
    data class BleAdvert(val serviceUuids: List<String>) : Evidence

    /** A LAN service found over mDNS with service [type]. */
    data class LanService(val type: String) : Evidence

    /** The `INFO` a connected Thing answered. */
    data class Info(val info: DeviceInfo) : Evidence
}

/**
 * A Thing [kind] recognized. [capabilities] are what the evidence proves it can do, empty when the
 * evidence says nothing about them (an advertisement). [thing] is its UUID when the evidence names one.
 */
data class Recognition(
    val kind: KindId,
    val capabilities: Set<Capability> = emptySet(),
    val thing: String? = null,
)

/**
 * Where a kind's data comes from. Only a [Recording] source carries a [PublishPolicy], so a
 * [PrivateRead] or [RemoteFeed] kind can never be published, whatever else it declares.
 */
sealed interface DataSource {
    /** A source that writes verified Parquet under `archive/thing=<uuid>/`. */
    sealed interface Recording : DataSource {
        val publish: PublishPolicy
    }

    /**
     * Files pulled from a board's own archive. The links stay with today's collection path,
     * `CollectionController` behind `DeviceFacade` on Android and `SensorCollections` on iOS.
     */
    class PullSync(override val publish: PublishPolicy) : Recording

    /** Things that are already public, read from [feed]. Nothing is written to `archive/`. */
    class RemoteFeed(val feed: PublicFeed) : DataSource

    /** Data read on the phone only, such as health. It never becomes an archive file and is never published. */
    class PrivateRead(val reader: PrivateSourcePort) : DataSource
}

/** Reads data that stays on the phone. Its readers arrive with their kinds. */
interface PrivateSourcePort

/** Whether a [DataSource.Recording] kind's files may ever be published. The owner still opts in per Thing. */
sealed interface PublishPolicy {
    /** Never published. */
    data object Never : PublishPolicy

    /**
     * Published under CC BY 4.0 once the owner opts the Thing in, with no cell finer than [maxRes].
     * [trimSessionEnds] drops the ends of a moving session, which a recording kind applies.
     */
    data class OptInCcBy(val maxRes: Int, val trimSessionEnds: Boolean) : PublishPolicy {
        init {
            require(maxRes in 0..15) { "an H3 resolution is 0 to 15" }
        }
    }
}

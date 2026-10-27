package space.opensensor.collection

import space.opensensor.model.ThingId

/**
 * What the user or a worker asked for. [id] is unique per request, so a Stop or a timer that names
 * an older request never touches a newer one. [device] is the [ThingId] the phone collects from, a
 * [ThingId.BleAddress] for a board. [clockConfirmed] means the phone may send its time when the
 * device clock needs it.
 */
data class CollectionIntent(
    val id: Long,
    val device: ThingId,
    val scope: CollectionScope,
    val origin: CollectionOrigin,
    val clockConfirmed: Boolean = false,
    /** Restricts every connection attempt, including retries, to the allowed transport. */
    val linkPreference: LinkPreference = LinkPreference.ANY,
)

/** Which listed files the collection covers. Both plan from a fresh `LIST`. */
sealed interface CollectionScope {
    /** Every listed file this phone lacks. */
    data object All : CollectionScope

    /** One listed file by its relative name, the Files screen retry. */
    data class One(val name: String) : CollectionScope
}

enum class CollectionOrigin { USER_TAP, WORKER }

/** Who ended a collection on purpose. */
enum class StopSource {
    /** The Stop button. */
    USER,

    /** The app closed the link on purpose, for a disconnect or a forgotten device. */
    APP,

    /** The OS ended the background time the collection ran in. */
    SYSTEM,

    /** The OS expired the foreground service's data-sync time allowance. */
    FGS_TIMEOUT,
}

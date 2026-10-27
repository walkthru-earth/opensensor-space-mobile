package space.opensensor.data.sync

/**
 * Where one Thing's data comes from, keyed by its UUID (#223). [kind] and [kindVersion] are the
 * `ThingKind` id and contract version that recorded it, and [configJson] is that kind's own settings
 * as a JSON object. It never holds a PIN, a Wi-Fi password, a LAN token or a location.
 */
data class ThingSource(
    val thing: String,
    val kind: String,
    val kindVersion: Int,
    val configJson: String = EMPTY_CONFIG,
) {
    init {
        require(thing.isNotBlank()) { "a Thing UUID is not blank" }
        require(kind.isNotBlank()) { "a kind id is not blank" }
    }

    companion object {
        /** The settings of a kind that keeps none. */
        const val EMPTY_CONFIG = "{}"
    }
}

/**
 * The phone's Thing sources, one per Thing UUID. Both phones keep them in the Room `thing_source`
 * table. A source outlives the Bluetooth row of its board, so forgetting a board keeps it.
 */
interface ThingSources {
    suspend fun get(thing: String): ThingSource?

    /** Every source, by Thing UUID. */
    suspend fun all(): List<ThingSource>

    /** Adds [source] unless its Thing already has one, which stays as it is. True when it was added. */
    suspend fun recordIfAbsent(source: ThingSource): Boolean

    /** Keeps nothing. */
    object None : ThingSources {
        override suspend fun get(thing: String): ThingSource? = null
        override suspend fun all(): List<ThingSource> = emptyList()
        override suspend fun recordIfAbsent(source: ThingSource): Boolean = false
    }
}

package earth.walkthru.aqsync.archive

/** A device-observed UTC/monotonic pair. Monotonic time is meaningful only within this station and boot. */
data class ClockAnchor(
    val station: String,
    val boot: String,
    val utcNs: Long,
    val monoUs: Long,
    val source: String = "phone",
) {
    init {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id" }
        require(boot.length == 32 && boot.all { it in '0'..'9' || it in 'a'..'f' }) { "invalid boot id" }
        require(utcNs in 1_577_836_800_000_000_000L..4_102_444_800_000_000_000L) { "UTC outside supported range" }
        require(monoUs in 0..Long.MAX_VALUE / 1_000L) { "invalid monotonic time" }
        require(source.length in 1..32 && source.all { it.isAsciiIdentifier() }) { "invalid clock source" }
    }
}

/** Durable clock evidence survives app restarts and never modifies an original device file. */
interface ClockAnchorStore {
    /** Earliest monotonic anchor of each boot, restricted to [station]. */
    suspend fun anchors(station: String): List<ClockAnchor>

    /** Persist an immutable receipt before derived history is rebuilt. Duplicate inserts are harmless. */
    suspend fun put(anchor: ClockAnchor)
}

object EmptyClockAnchorStore : ClockAnchorStore {
    override suspend fun anchors(station: String): List<ClockAnchor> = emptyList()
    override suspend fun put(anchor: ClockAnchor) = Unit
}

private fun Char.isAsciiIdentifier(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this == '_' || this == '-'

/** Small, versioned receipts have the same encoding on both platforms. No platform JSON dependency. */
internal object ClockAnchorReceipt {
    const val MAX_BYTES = 4096L

    fun relativePath(anchor: ClockAnchor): String = "${anchor.boot}/${anchor.monoUs}-${anchor.utcNs}-${anchor.source}.anchor"

    fun encode(anchor: ClockAnchor): ByteArray =
        "1\n${anchor.station}\n${anchor.boot}\n${anchor.utcNs}\n${anchor.monoUs}\n${anchor.source}\n".encodeToByteArray()

    fun decode(bytes: ByteArray, station: String, relativePath: String): ClockAnchor? {
        if (bytes.size > MAX_BYTES) return null
        val parts = bytes.decodeToString().split('\n')
        if (parts.size != 7 || parts[0] != "1" || parts[6].isNotEmpty()) return null
        val utc = parts[3].toLongOrNull() ?: return null
        val mono = parts[4].toLongOrNull() ?: return null
        val anchor = try {
            ClockAnchor(parts[1], parts[2], utc, mono, parts[5])
        } catch (_: IllegalArgumentException) {
            return null
        }
        return anchor.takeIf { it.station == station && relativePath(it) == relativePath }
    }

    fun earliest(anchors: List<ClockAnchor>): List<ClockAnchor> =
        anchors.sortedWith(compareBy(ClockAnchor::boot, ClockAnchor::monoUs, ClockAnchor::utcNs, ClockAnchor::source))
            .distinctBy(ClockAnchor::boot)
}

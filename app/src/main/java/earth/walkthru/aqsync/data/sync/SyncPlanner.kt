package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.Frame

/**
 * Where a manifest entry sits in the device's Hive tree.
 *
 * Names are the relative paths the firmware prints:
 * `station=…/year=…/month=…/day=…/data_HHMM_<boot>_<first>-<last>-<n>.parquet`,
 * `station=…/unsynced/boot=…/…`, `benchmarks/boot=…/…`, or `legacy-parquet/…`.
 */
sealed interface HivePartition {
    /** Rows with UTC: `station=…/year=YYYY/month=MM/day=DD/`. */
    data class Dated(val station: String, val year: Int, val month: Int, val day: Int) : HivePartition {
        val isoDate: String get() = "%04d-%02d-%02d".format(year, month, day)
    }

    /** Rows sampled before the clock was set: `station=…/unsynced/boot=<id>/`. */
    data class Unsynced(val station: String, val boot: String) : HivePartition

    /** `benchmarks/boot=<id>/…` */
    data class Benchmarks(val boot: String?) : HivePartition

    /** `legacy-parquet/…` */
    data object Legacy : HivePartition

    /** Anything that does not match the documented shapes; still synced verbatim. */
    data class Other(val directory: String) : HivePartition

    companion object {
        private val DATED = Regex("""^station=([^/]+)/year=(\d{4})/month=(\d{1,2})/day=(\d{1,2})/[^/]+$""")
        private val UNSYNCED = Regex("""^station=([^/]+)/unsynced/boot=([^/]+)/[^/]+$""")
        private val BENCH = Regex("""^benchmarks/(?:boot=([^/]+)/)?[^/]+$""")

        fun of(name: String): HivePartition {
            DATED.matchEntire(name)?.let {
                val (station, y, m, d) = it.destructured
                return Dated(station, y.toInt(), m.toInt(), d.toInt())
            }
            UNSYNCED.matchEntire(name)?.let {
                val (station, boot) = it.destructured
                return Unsynced(station, boot)
            }
            BENCH.matchEntire(name)?.let { return Benchmarks(it.groupValues[1].ifEmpty { null }) }
            if (name.startsWith("legacy-parquet/")) return Legacy
            return Other(name.substringBeforeLast('/', missingDelimiterValue = ""))
        }
    }
}

/**
 * Same character whitelist as `safe_name()` in the firmware: `[0-9a-z._=/-]`, no `..`, no `//`,
 * ends in `.parquet`, at most [MAX_BYTES] UTF-8 bytes (`ble-sync-protocol.md`, "names are ≤ 399
 * bytes", so every `OPENED` fits one PDU at MTU 517).
 */
object SafeName {
    /** Contract limit on a file name, in bytes. */
    const val MAX_BYTES = 399

    private val ALLOWED = Regex("""^[0-9a-z._=/-]+$""")

    fun isValid(name: String): Boolean =
        name.isNotEmpty() &&
            name.encodeToByteArray().size <= MAX_BYTES &&
            ALLOWED.matches(name) &&
            !name.contains("..") &&
            !name.contains("//") &&
            !name.startsWith("/") &&
            name.endsWith(".parquet")
}

/** Identity of a file already held in the local archive. */
data class ArchivedIdentity(val station: String, val name: String, val bytes: Long, val crc32: Long?)

/** Per-manifest-entry state as shown in the Files screen. */
enum class FileSyncState { NOT_SYNCED, PARTIAL, SYNCING, SYNCED, MISMATCH, INVALID_NAME }

data class ManifestEntry(
    val name: String,
    val bytes: Long,
    val partition: HivePartition,
    val state: FileSyncState,
    /** Bytes already on disk for a resumable partial, else 0. */
    val partialBytes: Long = 0,
    val detail: String? = null,
)

data class SyncPlan(
    val entries: List<ManifestEntry>,
    val toDownload: List<ManifestEntry>,
) {
    val missingBytes: Long get() = toDownload.sumOf { it.bytes - it.partialBytes }
    val missingCount: Int get() = toDownload.size
    val syncedCount: Int get() = entries.count { it.state == FileSyncState.SYNCED }
}

/**
 * Pure decision logic for "which files still need to come over".
 *
 * Archive identity is `(station, relative name)`. The file is immutable, so a differing `bytes`
 * for an already-archived name is a mismatch to surface, never a version to merge.
 */
object SyncPlanner {
    fun plan(
        station: String,
        manifest: List<Frame.File>,
        archived: Collection<ArchivedIdentity>,
        partials: Map<String, Long> = emptyMap(),
    ): SyncPlan {
        val byName = archived.filter { it.station == station }.associateBy { it.name }
        val entries = manifest.map { file ->
            val partition = HivePartition.of(file.name)
            val local = byName[file.name]
            val partial = partials[file.name] ?: 0L
            when {
                !SafeName.isValid(file.name) -> ManifestEntry(
                    file.name,
                    file.bytes,
                    partition,
                    FileSyncState.INVALID_NAME,
                    detail = "name violates the safe_name() whitelist; refusing to store it"
                )
                local == null && partial in 1 until file.bytes -> ManifestEntry(
                    file.name,
                    file.bytes,
                    partition,
                    FileSyncState.PARTIAL,
                    partialBytes = partial
                )
                local == null -> ManifestEntry(file.name, file.bytes, partition, FileSyncState.NOT_SYNCED)
                local.bytes != file.bytes -> ManifestEntry(
                    file.name,
                    file.bytes,
                    partition,
                    FileSyncState.MISMATCH,
                    detail = "archived copy is ${local.bytes} bytes, device reports ${file.bytes}"
                )
                else -> ManifestEntry(file.name, file.bytes, partition, FileSyncState.SYNCED)
            }
        }
        val toDownload = entries.filter { it.state == FileSyncState.NOT_SYNCED || it.state == FileSyncState.PARTIAL }
            .sortedWith(compareBy<ManifestEntry> { it.partition !is HivePartition.Dated }.thenBy { it.name })
        return SyncPlan(entries, toDownload)
    }
}

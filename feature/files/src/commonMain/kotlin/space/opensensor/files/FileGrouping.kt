package space.opensensor.files

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import space.opensensor.data.sync.FileSyncState
import space.opensensor.data.sync.HivePartition
import space.opensensor.data.sync.ManifestEntry
import kotlin.time.Instant

/**
 * One file shown in the list, with its window start as `HH:mm` in the phone's zone (null when the
 * name carries no time). [thing] is the archive folder the verified copy lives in, when known.
 */
data class FileItem(val entry: ManifestEntry, val localTime: String?, val thing: String? = null) {
    /** Unique across things, for list keys. */
    val key: String get() = "${thing.orEmpty()}/${entry.name}"
}

/**
 * Files shown under one header, in display order.
 *
 * Dated files are grouped by the **local** calendar day of their window start, not by the SD card's
 * `day=` folder. The firmware partitions and names files in UTC (`day=DD/data_HHMM_...`), so east of
 * Greenwich the evening's files sit in tomorrow's folder and, west of it, the morning's in
 * yesterday's. The archive path stays verbatim, only the header changes.
 */
data class PartitionGroup(val partition: HivePartition, val title: GroupTitle, val detail: GroupDetail, val entries: List<FileItem>) {
    val bytes: Long get() = entries.sumOf { it.entry.bytes }
    val synced: Int get() = entries.count { it.entry.state == FileSyncState.SYNCED }
    val key: String get() = "$title|$detail"
}

/** What a group header names. The screen maps each to a string resource. */
sealed interface GroupTitle {
    /** A local calendar day, formatted for the locale by the screen. */
    data class LocalDay(val date: LocalDate) : GroupTitle

    /** A dated folder whose file names carry no window time, named by its UTC day. */
    data class UtcDay(val isoDate: String) : GroupTitle
    data object Unsynced : GroupTitle
    data object Benchmarks : GroupTitle
    data object Legacy : GroupTitle

    /** An unrecognised folder, [directory] is empty for the archive root. */
    data class Other(val directory: String) : GroupTitle
}

/** Developer facts under a group header, identifiers and never sentences. */
data class GroupDetail(
    val thing: String? = null,
    val boot: String? = null,
    val utcFolders: List<String> = emptyList(),
    val unrecognised: Boolean = false,
)

/** Pure display grouping of listed or archived files. `zone` is a parameter so tests can pin it. */
object FileGrouping {
    /** `data_HHMM_<boot>_<first>-<last>-<n>.parquet`, HHMM is the UTC start of the file's window. */
    private val WINDOW = Regex("""^data_(\d{2})(\d{2})_""")

    /** UTC window start of a dated file, from its Hive day and its name, null for any other layout. */
    fun windowStartUtc(entry: ManifestEntry): Instant? {
        val p = entry.partition as? HivePartition.Dated ?: return null
        val m = WINDOW.find(entry.name.substringAfterLast('/')) ?: return null
        val (hh, mm) = m.destructured
        return runCatching { LocalDateTime(p.year, p.month, p.day, hh.toInt(), mm.toInt()).toInstant(TimeZone.UTC) }.getOrNull()
    }

    /** Groups [entries], each with the thing folder it belongs to (null when unknown). */
    fun group(entries: List<Pair<ManifestEntry, String?>>, zone: TimeZone): List<PartitionGroup> {
        data class Key(val rank: Int, val partition: HivePartition, val localDay: LocalDate?)

        fun hhmm(t: Instant): String {
            val l = t.toLocalDateTime(zone)
            return l.hour.toString().padStart(2, '0') + ":" + l.minute.toString().padStart(2, '0')
        }

        val byKey = entries.groupBy { (e, _) ->
            val p = e.partition
            val start = windowStartUtc(e)
            when {
                p is HivePartition.Dated && start != null -> {
                    val day = start.toLocalDateTime(zone).date
                    // One group per thing and local day.
                    Key(0, HivePartition.Dated(p.thing, day.year, day.month.ordinal + 1, day.day), day)
                }
                else -> Key(rank(p), p, null)
            }
        }
        return byKey.map { (key, list) ->
            val p = key.partition
            val items = list
                .sortedWith(compareByDescending<Pair<ManifestEntry, String?>> { windowStartUtc(it.first) ?: Instant.DISTANT_PAST }.thenByDescending { it.first.name })
                .map { (e, thing) -> FileItem(e, windowStartUtc(e)?.let(::hhmm), thing) }
            val (title, detail) = when (p) {
                is HivePartition.Dated if key.localDay != null -> {
                    val utcDays = list.map { (it.first.partition as HivePartition.Dated).isoDate }.distinct().sorted()
                    GroupTitle.LocalDay(key.localDay) to GroupDetail(thing = p.thing.take(8), utcFolders = utcDays)
                }
                // A dated folder whose file names carry no window time, shown as the UTC day it came from.
                is HivePartition.Dated -> GroupTitle.UtcDay(p.isoDate) to GroupDetail(thing = p.thing.take(8))
                is HivePartition.Unsynced -> GroupTitle.Unsynced to GroupDetail(boot = p.boot.take(12))
                is HivePartition.Benchmarks -> GroupTitle.Benchmarks to GroupDetail(boot = p.boot?.take(12))
                HivePartition.Legacy -> GroupTitle.Legacy to GroupDetail()
                is HivePartition.Other -> GroupTitle.Other(p.directory) to GroupDetail(unrecognised = true)
            }
            key to PartitionGroup(p, title, detail, items)
        }.sortedWith(
            compareBy<Pair<Key, PartitionGroup>> { it.first.rank }
                .thenByDescending { it.first.localDay ?: LocalDate(1, 1, 1) }
                .thenByDescending { (it.first.partition as? HivePartition.Dated)?.isoDate ?: "" }
                .thenBy { it.second.key },
        ).map { it.second }
    }

    private fun rank(p: HivePartition) = when (p) {
        is HivePartition.Dated -> 0
        is HivePartition.Unsynced -> 1
        is HivePartition.Benchmarks -> 2
        HivePartition.Legacy -> 3
        is HivePartition.Other -> 4
    }
}

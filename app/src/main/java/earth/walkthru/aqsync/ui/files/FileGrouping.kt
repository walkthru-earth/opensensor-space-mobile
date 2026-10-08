package earth.walkthru.aqsync.ui.files

import earth.walkthru.aqsync.data.sync.HivePartition
import earth.walkthru.aqsync.data.sync.ManifestEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One manifest entry with its window start rendered in the phone's zone (null when the name carries no time). */
data class FileItem(val entry: ManifestEntry, val localTime: String?)

/**
 * A group of manifest entries shown under one header, in display order.
 *
 * Dated files are grouped by the **local** calendar day of their window start, not by the SD card's
 * `day=` folder: the firmware partitions and names files in UTC (`day=DD/data_HHMM_…`), so east of
 * Greenwich the evening's files sit in tomorrow's folder and, west of it, the morning's in
 * yesterday's. The archive path stays verbatim; only the header changes.
 */
data class PartitionGroup(val partition: HivePartition, val title: GroupTitle, val detail: GroupDetail, val entries: List<FileItem>) {
    val bytes: Long get() = entries.sumOf { it.entry.bytes }
    val key: String get() = "$title|$detail"
}

/** What a group header names. The UI maps each to a string resource (`FilesScreen`). */
sealed interface GroupTitle {
    /** A local calendar day, already formatted for the locale. */
    data class LocalDay(val text: String) : GroupTitle

    /** A dated folder whose file names carry no window time, named by its UTC day. */
    data class UtcDay(val isoDate: String) : GroupTitle
    data object Unsynced : GroupTitle
    data object Benchmarks : GroupTitle
    data object Legacy : GroupTitle

    /** An unrecognised folder; [directory] is empty for the archive root. */
    data class Other(val directory: String) : GroupTitle
}

/** Developer facts under a group header: identifiers, never sentences. */
data class GroupDetail(
    val station: String? = null,
    val boot: String? = null,
    val utcFolders: List<String> = emptyList(),
    val unrecognised: Boolean = false,
)

/** Pure display grouping of a sync plan's entries; `zone` is a parameter so tests can pin it. */
object FileGrouping {
    /** `data_HHMM_<boot>_<first>-<last>-<n>.parquet` — HHMM is the UTC start of the file's window. */
    private val WINDOW = Regex("""^data_(\d{2})(\d{2})_""")

    /** UTC window start of a dated file, from its Hive day and its name; null for any other layout. */
    fun windowStartUtc(entry: ManifestEntry): Instant? {
        val p = entry.partition as? HivePartition.Dated ?: return null
        val m = WINDOW.find(entry.name.substringAfterLast('/')) ?: return null
        val (hh, mm) = m.destructured
        return runCatching { LocalDateTime.of(p.year, p.month, p.day, hh.toInt(), mm.toInt()).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    fun group(entries: List<ManifestEntry>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<PartitionGroup> {
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM uuuu", locale)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone)

        data class Key(val rank: Int, val partition: HivePartition, val localDay: LocalDate?)

        val byKey = entries.groupBy { e ->
            val p = e.partition
            val start = windowStartUtc(e)
            when {
                p is HivePartition.Dated && start != null -> {
                    val day = start.atZone(zone).toLocalDate()
                    // One group per station and local day; the representative partition is the first UTC folder seen.
                    Key(0, HivePartition.Dated(p.station, day.year, day.monthValue, day.dayOfMonth), day)
                }
                else -> Key(rank(p), p, null)
            }
        }
        return byKey.map { (key, list) ->
            val p = key.partition
            val items = list.sortedWith(compareByDescending<ManifestEntry> { windowStartUtc(it) ?: Instant.MIN }.thenByDescending { it.name })
                .map { e -> FileItem(e, windowStartUtc(e)?.let { timeFmt.format(it) }) }
            val (title, detail) = when {
                p is HivePartition.Dated && key.localDay != null -> {
                    val utcDays = list.map { (it.partition as HivePartition.Dated).isoDate }.distinct().sorted()
                    GroupTitle.LocalDay(dayFmt.format(key.localDay)) to GroupDetail(station = p.station.take(8), utcFolders = utcDays)
                }
                // A dated folder whose file names carry no window time: shown as the UTC day it came from.
                p is HivePartition.Dated -> GroupTitle.UtcDay(p.isoDate) to GroupDetail(station = p.station.take(8))
                p is HivePartition.Unsynced -> GroupTitle.Unsynced to GroupDetail(boot = p.boot.take(12))
                p is HivePartition.Benchmarks -> GroupTitle.Benchmarks to GroupDetail(boot = p.boot?.take(12))
                p is HivePartition.Legacy -> GroupTitle.Legacy to GroupDetail()
                p is HivePartition.Other -> GroupTitle.Other(p.directory) to GroupDetail(unrecognised = true)
                else -> error("unreachable")
            }
            key to PartitionGroup(p, title, detail, items)
        }.sortedWith(
            compareBy<Pair<Key, PartitionGroup>> { it.first.rank }
                .thenByDescending { it.first.localDay ?: LocalDate.MIN }
                .thenByDescending { (it.first.partition as? HivePartition.Dated)?.isoDate ?: "" }
                .thenBy { it.second.key }
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

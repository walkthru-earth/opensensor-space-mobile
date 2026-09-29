package earth.walkthru.aqsync.ui.history

import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.sync.HivePartition
import java.time.Instant
import java.time.ZoneId

/** Keep the archive namespace from INFO distinct from the producer station inside a Hive path. */
internal data class HistoryFileCounts(
    val withTime: Int = 0,
    val total: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val otherStation: Int = 0,
    val estimated: Int = 0,
    val untimed: Int = 0,
)

internal fun countHistoryFiles(files: List<ArchivedFile>): HistoryFileCounts {
    val (other, own) = files.partition { file ->
        val producer = when (val partition = HivePartition.of(file.name)) {
            is HivePartition.Dated -> partition.station
            is HivePartition.Unsynced -> partition.station
            else -> null
        }
        producer != null && producer != file.station
    }
    val telemetry = own.filter { it.purpose == null || it.purpose == "telemetry" }
    return HistoryFileCounts(
        withTime = telemetry.count { it.indexed && it.hasUtc },
        total = own.size,
        pending = own.count { it.indexedAtEpochMs == null },
        failed = own.count { it.indexError != null },
        otherStation = other.size,
        estimated = telemetry.count { it.indexed && it.hasEstimatedUtc },
        untimed = telemetry.count { it.indexed && !it.hasUtc && !it.hasEstimatedUtc },
    )
}

/** Show the latest station with actual timeline data when the current generation has none. */
internal fun preferredHistoryStation(
    current: String?,
    stations: List<String>,
    files: List<ArchivedFile>,
    selected: String?,
): String? {
    if (selected != null && selected in stations) return selected
    if (current == null || current !in stations) return current
    val timed = files.filter {
        it.station in stations && it.indexed && (it.hasUtc || it.hasEstimatedUtc) &&
            (it.purpose == null || it.purpose == "telemetry")
    }
    if (timed.any { it.station == current }) return current
    return timed.maxByOrNull { it.timelineLastNs ?: Long.MIN_VALUE }?.station ?: current
}

/** End of the local calendar day containing the station's most recent archived reading. */
internal fun latestHistoryDayEnd(files: List<ArchivedFile>, zone: ZoneId): Long? = files.asSequence()
    .filter { it.indexed && (it.purpose == null || it.purpose == "telemetry") }
    .mapNotNull { it.timelineLastNs }
    .maxOrNull()
    ?.let { ns -> Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L)).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond() }

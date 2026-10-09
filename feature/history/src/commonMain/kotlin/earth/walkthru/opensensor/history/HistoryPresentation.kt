package earth.walkthru.opensensor.history

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.history.ArchiveIndexRepair
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.data.history.startOfDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** The archive card's counts. A copy whose Hive path names another producer station is kept apart. */
data class HistoryFileCounts(
    val withTime: Int = 0,
    val total: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val otherStation: Int = 0,
    val estimated: Int = 0,
    val untimed: Int = 0,
)

fun countHistoryFiles(files: List<ArchiveRecord>): HistoryFileCounts {
    val (other, own) = files.partition { file ->
        val producer = ArchiveIndexRepair.producerStation(file.name)
        producer != null && producer != file.station
    }
    val telemetry = own.filter { it.isTelemetry }
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

/**
 * The station History opens on. An explicit chip choice wins. Otherwise [current], unless it has no
 * timed readings yet while another of [stations] has, then the one with the latest reading.
 */
fun preferredHistoryStation(current: String?, stations: List<String>, files: List<ArchiveRecord>, selected: String?): String? {
    if (selected != null && selected in stations) return selected
    if (current == null || current !in stations) return current
    val timed = files.filter { it.station in stations && it.indexed && (it.hasUtc || it.hasEstimatedUtc) && it.isTelemetry }
    if (timed.any { it.station == current }) return current
    return timed.maxByOrNull { it.timelineLastNs ?: Long.MIN_VALUE }?.station ?: current
}

/** End of the local calendar day containing the station's most recent archived reading. */
fun latestHistoryDayEnd(files: List<ArchiveRecord>, zone: TimeZone): Long? = files.asSequence()
    .filter { it.indexed && it.isTelemetry }
    .mapNotNull { it.timelineLastNs }
    .maxOrNull()
    ?.let { ns -> startOfDay(localDateOf(ns.floorDiv(NS), zone).plus(1, DateTimeUnit.DAY), zone) }

/**
 * The archived stations ordered oldest reading first, so the last one is the most recent. iOS has no
 * station epoch table yet, so its chips are the stations of the phone archive.
 */
fun archiveStations(files: List<ArchiveRecord>): List<String> = files.groupBy { it.station }
    .map { (station, rows) -> station to (rows.filter { it.indexed && it.isTelemetry }.mapNotNull { it.timelineLastNs }.maxOrNull() ?: Long.MIN_VALUE) }
    .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
    .map { it.first }

private const val NS = 1_000_000_000L

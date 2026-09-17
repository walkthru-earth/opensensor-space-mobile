package earth.walkthru.aqsync.data.history

import android.util.Log
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedDay
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.data.local.database.MetricBucket
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the aggregate tiers (`aggregate_bucket`) in step with the archive, one UTC station-day at a
 * time. A day is (re)built when the set of verified, indexed, telemetry files that carry UTC rows
 * overlapping it differs from the set recorded in `derived_day` — so a late-arriving file rebuilds
 * exactly its day, and an unchanged day costs one string comparison.
 *
 * Pure caches: dropping both tables and running [deriveStale] again gives the same rows. Nothing here
 * writes to the archive.
 */
@Singleton
class HistoryDeriver @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
    private val bucketDao: MetricBucketDao,
    private val dayDao: DerivedDayDao,
    private val store: DerivedStore,
    private val indexer: HistoryIndexer,
) {
    data class Result(val daysChecked: Int, val daysRebuilt: Int, val rowsAggregated: Long)

    private val dailyBuilder = DailyDerivedBuilder(store) { station, name -> archive.fileFor(station, name) }

    /** Passes are triggered from several places (screens, post-sync, worker); run one at a time so they never duplicate work. */
    private val mutex = Mutex()

    /** Which station-days a set of indexed files touches — by real UTC, or by the estimated span (§4.6). */
    fun daysOf(files: List<ArchivedFile>): Map<String, Set<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        for (f in files) {
            if (!f.indexed || !(f.purpose == null || f.purpose == "telemetry")) continue
            val first = f.timelineFirstNs ?: continue
            val last = f.timelineLastNs ?: continue
            val days = out.getOrPut(f.station) { HashSet() }
            var d = Math.floorDiv(first, DAY_NS)
            val lastDay = Math.floorDiv(last, DAY_NS)
            while (d <= lastDay) { days += d; d++ }
        }
        return out
    }

    /** Deterministic identity of a day's source set: sorted `name:crc32[:est<anchor>]` lines, SHA-256 (a new anchor re-dates a file, so it is part of the identity). */
    fun signature(files: List<ArchivedFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("derive-v$DERIVE_VERSION\n".toByteArray())
        files.map { "${it.name}:${it.crc32}" + (if (it.hasEstimatedUtc) ":est${it.estAnchorFile}:${it.estFirstUtcNs}" else "") }.sorted().forEach { digest.update(it.toByteArray()); digest.update('\n'.code.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Rebuilds every stale station-day. Cheap when nothing changed; a full first pass over a year of one
     * station is ~365 day rebuilds of ≤ 96 files each. Safe to call from any screen or worker; idempotent.
     */
    suspend fun deriveStale(stations: Collection<String>? = null): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            var checked = 0
            var rebuilt = 0
            var rows = 0L
            // A file that just arrived may be the first timed file of a boot: re-date that boot's unsynced files first.
            indexer.estimateTimes()
            val all = archivedFileDao.observeAll().first()
            val wanted = daysOf(all)
            val anchorsByStation = all.groupBy { it.station }.mapValues { (_, files) -> TimeEstimate.anchors(files.map { it.info() }) }
            // Stations that only have derived days left (every dated file gone) must be visited too, to drop them.
            val allStations = (wanted.keys + dayDao.stations()).filter { stations == null || it in stations }
            for (station in allStations) {
                val days = wanted[station] ?: emptySet()
                val known = dayDao.forStation(station).associateBy { it.epochDay }
                for (day in days.sorted()) {
                    checked++
                    val dayStartNs = day * DAY_NS
                    val sources = archivedFileDao.withUtcBetween(station, dayStartNs, dayStartNs + DAY_NS)
                    val sig = signature(sources)
                    if (known[day]?.sourceSignature == sig) continue
                    val built = buildDay(station, day, sources, anchorsByStation[station] ?: emptyMap())
                    rebuilt++
                    rows += built
                }
                // Days that no longer have any source (files deleted) lose their tiers too.
                for (stale in known.keys - days) {
                    bucketDao.replaceDay(station, stale * 86_400, emptyList())
                    dayDao.delete(station, stale)
                    store.dayFile(station, stale).delete()
                    store.manifestFile(station, stale).delete()
                    rebuilt++
                }
            }
            if (rebuilt > 0) Log.i(TAG, "derived $rebuilt of $checked station-days ($rows rows)")
            Result(checked, rebuilt, rows)
        }
    }

    /** Aggregates one station-day from [sources] and replaces its tiers. Returns rows aggregated. */
    suspend fun buildDay(station: String, epochDay: Long, sources: List<ArchivedFile>, anchors: Map<String, TimeEstimate.Anchor> = emptyMap()): Long {
        val dayStartS = epochDay * 86_400
        val agg = Aggregator(station)
        var rows = 0L
        for (src in sources) {
            val file = archive.fileFor(src.station, src.name)
            if (!file.isFile) continue
            try {
                val pf = ParquetFile.open(file)
                // Estimated time only for files that have no UTC of their own and whose boot has an anchor.
                agg.add(pf, if (src.hasEstimatedUtc) anchors[src.bootId] else null)
                rows += pf.numRows
            } catch (e: ParquetFormatException) {
                Log.w(TAG, "${src.name}: ${e.message}")
            }
        }
        // Keep only buckets that start inside this day: a file straddling midnight contributes its other
        // rows when the neighbouring day is built (it is in that day's source set too).
        val dayBuckets = agg.buckets().filter { it.startEpochS >= dayStartS && it.startEpochS < dayStartS + 86_400 }
        bucketDao.replaceDay(station, dayStartS, dayBuckets.flatMap { it.toEntities() })
        val sig = signature(sources)
        // The derived daily Parquet file: same sources, same moment. A failure here keeps the tiers and
        // leaves `dailyFile` null; the next derive pass tries again because the signature is then unchanged
        // only if the write also succeeded (see below).
        val daily = try {
            dailyBuilder.build(station, epochDay, sources, sig, anchors)
        } catch (e: Exception) {
            Log.w(TAG, "daily file for $station ${DerivedStore.dayLabel(epochDay)}: ${e.message}")
            null
        }
        dayDao.upsert(
            DerivedDay(
                station = station,
                epochDay = epochDay,
                // A missing daily file marks the day stale so it is retried on the next pass.
                sourceSignature = if (daily != null) sig else "$sig:no-daily-file",
                sourceFiles = sources.size,
                rows = dayBuckets.filter { it.resolution == Resolution.DAY }.sumOf { it.rows },
                builtAtEpochMs = System.currentTimeMillis(),
                dailyFile = daily?.file?.relativeTo(store.root)?.path,
            )
        )
        return rows
    }

    /** One row per metric that had values in the bucket. */
    private fun Bucket.toEntities(): List<MetricBucket> = metrics.map { (metricId, a) ->
        MetricBucket(
            station = station,
            metricId = metricId,
            resolution = resolution.seconds,
            startEpochS = startEpochS,
            rows = rows,
            validRows = a.n,
            estimatedRows = estimatedRows,
            mean = a.mean,
            min = a.min,
            max = a.max,
        )
    }

    companion object {
        private const val TAG = "HistoryDeriver"

        /** Part of every day's signature: bump when the tiers' meaning changes so every day is rebuilt. 2 = per-metric rows. */
        const val DERIVE_VERSION = 2
        const val DAY_NS = 86_400L * 1_000_000_000L
    }
}

package earth.walkthru.aqsync.data.history

import android.util.Log
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.archive.toRecord
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedDay
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.data.local.database.MetricBucket
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.storage.DerivedQuota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Brings derived history up to date after new files arrive; [HistoryDeriver] in the app. */
fun interface HistoryRefresher {
    suspend fun deriveStale(): HistoryDeriver.Result

    /** After a sync, rebuilds only the days that files archived since [sinceEpochMs] touch. */
    suspend fun deriveAfterSync(sinceEpochMs: Long): HistoryDeriver.Result = deriveStale()

    /** Persist an accepted collection clock receipt without starting a history pass. */
    suspend fun persistClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {}

    /** Re-dates and derives [station] in one History pass, after a clock receipt was persisted. */
    suspend fun deriveStation(station: String) {}
}

/** Queues the stale days a post-sync pass skipped for a full pass under a charging constraint. */
fun interface DeriveBackfill {
    fun schedule()
}

/**
 * Keeps the aggregate tiers (`aggregate_bucket`) in step with the archive, one UTC station-day at a
 * time. A day is (re)built when the set of verified, indexed, telemetry files that carry UTC rows
 * overlapping it differs from the set recorded in `derived_day` — so a late-arriving file rebuilds
 * exactly its day, and an unchanged day costs one string comparison.
 *
 * Pure caches: dropping both tables and running [deriveStale] again gives the same rows. Nothing here
 * writes to the archive. Passes come from several places (start, post-sync, worker, Settings, export),
 * and each runs through [HistoryOrchestrator.derivePass], one at a time and never beside a backfill.
 */
@Singleton
class HistoryDeriver @Inject constructor(
    private val archive: FileArchive,
    private val archivedFileDao: ArchivedFileDao,
    private val bucketDao: MetricBucketDao,
    private val dayDao: DerivedDayDao,
    private val store: DerivedStore,
    private val indexer: HistoryIndexer,
    private val quota: DerivedQuota,
    /** Runs every pass of this class as one shared History pass, after any backfill in flight. */
    private val orchestrator: HistoryOrchestrator,
    private val backfill: DeriveBackfill = DeriveBackfill {},
) : HistoryRefresher {
    /** [daysDeferred] counts stale days a post-sync pass left to [DeriveBackfill]. */
    data class Result(val daysChecked: Int, val daysRebuilt: Int, val rowsAggregated: Long, val daysDeferred: Int = 0)

    private val dailyBuilder = DailyDerivedBuilder(store, indexer.templates) { station, name -> archive.fileFor(station, name) }

    /** Which station-days a set of indexed files touches, by real UTC or by the estimated span (§4.6). */
    fun daysOf(files: List<ArchivedFile>): Map<String, Set<Long>> = StationDays.of(files.map { it.toRecord() })

    /** [signatureOf] for Room rows. */
    fun signature(files: List<ArchivedFile>): String = signatureOf(files.map { it.toRecord() })

    /** Only the accepted receipt; collection reconciliation runs in its cancellable finalization. */
    override suspend fun persistClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {
        indexer.persistClockAnchor(station, boot, utcNs, monotonicUs)
    }

    /**
     * Re-dates and derives [station] in one pass. The re-dating runs inside the pass ([deriveStale]
     * estimates first), never beside a derive or a backfill that reads the estimates.
     */
    override suspend fun deriveStation(station: String) {
        deriveStale(listOf(station))
    }

    override suspend fun deriveStale(): Result = deriveStale(null)

    /**
     * Rebuilds the days that files synced since [sinceEpochMs] touch, so a sync never pays for a
     * backfill. Any other stale day of those stations goes to [backfill].
     */
    override suspend fun deriveAfterSync(sinceEpochMs: Long): Result {
        val result = deriveStale(null, touchedSinceEpochMs = sinceEpochMs)
        if (result.daysDeferred > 0) {
            Log.i(TAG, "deferred ${result.daysDeferred} stale station-days to the charging backfill")
            backfill.schedule()
        }
        return result
    }

    suspend fun deriveStale(stations: Collection<String>? = null, touchedSinceEpochMs: Long? = null): Result = orchestrator.derivePass {
        withContext(Dispatchers.IO) {
            var checked = 0
            var rebuilt = 0
            var rows = 0L
            var deferred = 0
            // A file that just arrived may be the first timed file of a boot: re-date that boot's unsynced files first.
            indexer.estimateTimes()
            val all = archivedFileDao.observeAll().first()
            val wanted = daysOf(all)
            val byStation = all.groupBy { it.station }
            val anchorsByStation = byStation.mapValues { (_, files) -> TimeEstimate.anchors(files.map { it.info() }, indexer.clockAnchors.anchors(files.first().station)) }
            // Stations that only have derived days left (every dated file gone) must be visited too, to drop them.
            // Read after the re-dating above, so a file that just got its time counts on its real days.
            val touched = touchedSinceEpochMs?.let { since -> daysOf(all.filter { it.syncedAtEpochMs >= since }) }
            val allStations = (wanted.keys + dayDao.stations()).filter { (stations == null || it in stations) && (touched == null || it in touched) }
            for (station in allStations) {
                val days = wanted[station] ?: emptySet()
                val known = dayDao.forStation(station).associateBy { it.epochDay }
                // One listing for every day of the station, not a time query per day (#63).
                val sourcesByDay = sourcesByDay(byStation[station].orEmpty())
                for (day in days.sorted()) {
                    checked++
                    val sources = sourcesByDay[day].orEmpty()
                    val sig = signature(sources)
                    val cached = known[day]
                    // A missing day file with a current signature was evicted by the quota or the OS. It is a
                    // cache miss, rebuilt when the export reads it, not here, so eviction never churns.
                    if (cached?.sourceSignature == sig && cached.dailyFile != null) continue
                    if (touched != null && day !in touched.getValue(station)) {
                        deferred++
                        continue
                    }
                    val built = buildDay(station, day, sources, anchorsByStation[station] ?: emptyMap())
                    rebuilt++
                    rows += built
                }
                // Days that no longer have any source (files deleted) lose their tiers too. A day a file
                // still waiting for its index holds stays in History, the shared rule of StationDays.kept.
                val held = StationDays.held(byStation[station].orEmpty().map { it.toRecord() })
                val kept = StationDays.kept(known.keys, days, held)
                for (stale in known.keys - days - kept) {
                    bucketDao.replaceDay(station, stale * 86_400, emptyList())
                    dayDao.delete(station, stale)
                    store.dayFile(station, stale).delete()
                    store.manifestFile(station, stale).delete()
                    rebuilt++
                }
            }
            // "derived 0 of N" (debug) is the healthy line after a pass with nothing new — a rebuild on
            // every pass was the symptom of the @Upsert bug (README decisions, 2026-09-18).
            if (rebuilt > 0) Log.i(TAG, "derived $rebuilt of $checked station-days ($rows rows)") else Log.d(TAG, "derived 0 of $checked station-days (nothing changed)")
            if (rebuilt > 0) enforceQuota()
            Result(checked, rebuilt, rows, deferred)
        }
    }

    /**
     * Rebuilds the daily file of every derived day whose file is gone, evicted by the quota or cleared
     * by the OS. The daily zip export calls it before it reads the derived tree. The quota is not
     * enforced here, so the export sees every day, the next derive pass or janitor run trims it again.
     * Returns how many files it rebuilt.
     */
    suspend fun ensureDailyFiles(station: String? = null): Int = orchestrator.derivePass {
        withContext(Dispatchers.IO) {
            var rebuilt = 0
            val stations = if (station != null) listOf(station) else dayDao.stations()
            for (s in stations) {
                val missing = dayDao.forStation(s).filter { it.dailyFile != null && !store.dayFile(s, it.epochDay).isFile }
                if (missing.isEmpty()) continue
                val files = archivedFileDao.forStation(s)
                val anchors = TimeEstimate.anchors(files.map { it.info() }, indexer.clockAnchors.anchors(s))
                val sourcesByDay = sourcesByDay(files)
                for (day in missing) {
                    val sources = sourcesByDay[day.epochDay].orEmpty()
                    val sig = signature(sources)
                    try {
                        // The signature check keeps a stale day for the next derive pass, which rebuilds its tiers too.
                        if (day.sourceSignature != sig) continue
                        withContext(cpu) { dailyBuilder.build(s, day.epochDay, sources, sig, anchors) } ?: continue
                        rebuilt++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "daily file retry needed (${e::class.simpleName})")
                    }
                }
            }
            if (rebuilt > 0) Log.i(TAG, "rebuilt $rebuilt evicted daily files")
            rebuilt
        }
    }

    private suspend fun enforceQuota() {
        try {
            val report = quota.enforce()
            if (report.evicted > 0) Log.i(TAG, report.describe())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "derived quota retry needed (${e::class.simpleName})")
        }
    }

    /**
     * Aggregates one station-day from [sources], replaces its tiers and writes its daily file, from one
     * decode of each source (#63). Returns rows aggregated.
     */
    suspend fun buildDay(station: String, epochDay: Long, sources: List<ArchivedFile>, anchors: Map<String, TimeEstimate.Anchor> = emptyMap()): Long {
        val dayStartS = epochDay * 86_400
        val sig = signature(sources)
        val run = withContext(cpu) { dailyBuilder.pass(station, epochDay, sources, sig, anchors) }
        val pass = run.pass
        if (pass.failedSources.isNotEmpty()) Log.w(TAG, "${pass.failedSources.size} source reads failed")
        // The pass keeps only buckets that start inside this day: a file straddling midnight contributes
        // its other rows when the neighbouring day is built (it is in that day's source set too).
        val dayBuckets = pass.buckets
        bucketDao.replaceDay(station, dayStartS, dayBuckets.flatMap { it.toEntities() })
        // A failed daily file keeps the tiers and leaves `dailyFile` null, the next derive pass retries it.
        val daily = run.daily
        pass.dailyError?.let { Log.w(TAG, "daily file retry needed (${it::class.simpleName})") }
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
        return pass.rowsRead
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
            sampleIntervalMs = sampleIntervalMs,
        )
    }

    companion object {
        private const val TAG = "HistoryDeriver"

        /** Decode and aggregation are CPU work, two at most so a derive never takes every core from the UI. */
        private val cpu = Dispatchers.Default.limitedParallelism(2)

        /**
         * Deterministic identity of a day's source set: sorted `name:crc32[:est<anchor>]` lines, SHA-256
         * (a new anchor re-dates a file, so it is part of the identity). The Day view compares it to the
         * recorded one before it reads a daily file ([RoomDerivedDayFiles]).
         */
        fun signatureOf(files: List<ArchiveRecord>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("derive-v$DERIVE_VERSION\n".toByteArray())
            files.map { "${it.name}:${it.crc32}" + (if (it.hasEstimatedUtc) ":est${it.estAnchorFile}:${it.estFirstUtcNs}" else "") }.sorted().forEach { digest.update(it.toByteArray()); digest.update('\n'.code.toByte()) }
            return hex(digest.digest())
        }

        private val HEX = "0123456789abcdef".toCharArray()

        /** Lower-case hex, a table lookup per nibble instead of a format call per byte. */
        fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt()
                out[2 * i] = HEX[(b ushr 4) and 0xF]
                out[2 * i + 1] = HEX[b and 0xF]
            }
            return out.concatToString()
        }

        /** Each day's sources from one station listing, the same files and order as `withUtcBetween`. */
        fun sourcesByDay(files: List<ArchivedFile>): Map<Long, List<ArchivedFile>> = StationDays.sourcesByDay(files) { it.toRecord() }

        /** Part of every day's signature. 2 = per-metric rows, 3 = shared RowKey, 4 = single pass, 5 = canonical duplicate source order, 6 = MICROS UTC and field_id in the daily file, 7 = EPA corrected PM2.5 tier, 8 = template datastreams and the footer sample interval. */
        const val DERIVE_VERSION = 8
        const val DAY_NS = StationDays.DAY_NS
    }
}

package space.opensensor.data.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import space.opensensor.archive.FileClockAnchorStore
import space.opensensor.data.archive.FileArchiveStore
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetFormatException
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.local.database.MetricBucketDao
import space.opensensor.data.sync.FileArchive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's History source: the shared [HistorySeriesReader] over Room (`archived_file`,
 * `metric_bucket`) and the files in `filesDir`. Week, Month and Year read the tiers, Day the current
 * derived day files ([RoomDerivedDayFiles]) and the raw rows of the other days.
 * Reactive and read only. [fileSummaries] serves the Android Files screen.
 */
@Singleton
class HistoryRepository internal constructor(
    private val archive: FileArchive,
    archivedFileDao: ArchivedFileDao,
    bucketDao: MetricBucketDao,
    derived: DerivedDayFiles,
    /** Coverage counts only the elapsed part of a window holding now. Tests pin it. */
    nowEpochS: () -> Long,
    /** Resolves each file's board template from its footer. Tests read old fixtures through their own. */
    private val templates: FileTemplates = FileTemplates.BUNDLED,
) {
    @Inject constructor(archive: FileArchive, archivedFileDao: ArchivedFileDao, bucketDao: MetricBucketDao, derived: DerivedDayFiles) :
        this(archive, archivedFileDao, bucketDao, derived, { System.currentTimeMillis() / 1_000 })

    val index = RoomArchiveIndex(archivedFileDao)
    val store = FileArchiveStore(archive, Dispatchers.IO)
    val tiers: HistoryTiers = RoomHistoryTiers(bucketDao)
    private val clockAnchors = FileClockAnchorStore(File(archive.root.parentFile, "clock-anchors"))
    val reader = HistorySeriesReader(store, index, tiers, Dispatchers.IO, clockAnchors, derived, nowEpochS = nowEpochS, templates = templates)

    fun observe(station: String, metric: MetricSpec, range: HistoryRange, window: HistoryWindow): Flow<HistorySeries> =
        reader.observe(station, metric, range, window)

    /** Today card, the same exact local-day bounds from hourly tiers plus raw boundary rows. */
    fun observeHourlyDay(station: String, metric: MetricSpec, window: HistoryWindow): Flow<HistorySeries> =
        reader.observeHourlyDay(station, metric, window)

    /**
     * "Chart by file", one [FileSummary] per archived telemetry file of [station], oldest first. Timed
     * files by their (real or estimated) first row, then the untimed ones by name. Reads every file
     * once (about 40 KB each, footer and a few columns). Files are immutable, so the result is cached
     * by `(station, name, crc32)` for the life of the process.
     */
    suspend fun fileSummaries(station: String, files: List<ArchivedFile>): List<FileSummary> = withContext(Dispatchers.IO) {
        val telemetry = files.filter { it.isIndexed && (it.purpose == null || it.purpose == "telemetry") }
        val anchors = if (telemetry.any { it.hasEstimatedUtc }) TimeEstimate.anchors(telemetry.map { it.info() }, clockAnchors.anchors(station)) else emptyMap()
        val ordered = telemetry.sortedWith(compareBy<ArchivedFile> { it.timelineFirstNs == null }.thenBy { it.timelineFirstNs ?: 0L }.thenBy { it.name })
        ordered.mapNotNull { f ->
            val key = Triple(f.station, f.name, f.crc32) to f.estFirstUtcNs
            synchronized(summaryCache) { summaryCache[key] }?.let { return@mapNotNull it }
            val file = archive.fileFor(f.station, f.name)
            if (!file.isFile) return@mapNotNull null
            try {
                ParquetFile.open(file).use { FileSummary.of(f.name, it, if (f.hasEstimatedUtc) anchors[f.bootId] else null, templates = templates) }
                    .also { synchronized(summaryCache) { summaryCache[key] = it } }
            } catch (e: ParquetFormatException) {
                null
            }
        }
    }

    /** Immutable files give immutable summaries. The estimate's first UTC is part of the key because an anchor re-dates a file. */
    private val summaryCache = HashMap<Pair<Triple<String, String, Long>, Long?>, FileSummary>()

    companion object {
        /** Above this many rows the day view bins to minutes. */
        const val RAW_POINT_CAP = HistorySeriesReader.RAW_POINT_CAP
    }
}

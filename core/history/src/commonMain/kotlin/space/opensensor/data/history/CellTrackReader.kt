package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.data.history.parquet.ParquetFile

/**
 * Reads a Thing's [CellTrack] from the verified files on this phone (#263). Each row gives its own
 * `h3_cell_id` and `location_source`. A file without those columns gives rows with no fix, and a row
 * with no UTC time is left out. Pure reads, nothing here writes to the archive or the index.
 */
class CellTrackReader(
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The track of the rows in `[fromNs, toNs)`. A file that cannot be read costs only its own rows. */
    suspend fun read(thing: String, fromNs: Long, toNs: Long): CellTrack {
        if (toNs <= fromNs) return CellTrack.EMPTY
        val rows = ArrayList<TrackRow>()
        for (f in index.timeline(thing, fromNs, toNs)) readFile(f, fromNs, toNs, rows)
        return CellTrack.of(rows)
    }

    /**
     * The track of the last [spanNs] before [nowNs], read again when the Thing's timeline changes (a
     * synced or indexed file).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(thing: String, spanNs: Long, nowNs: () -> Long): Flow<CellTrack> =
        index.observeThing(thing)
            .map { files -> files.filter { it.isIndexed }.map { Triple(it.name, it.crc32, it.timelineFirstNs) } }
            .distinctUntilChanged()
            .mapLatest {
                val to = nowNs()
                read(thing, to - spanNs, to)
            }
            .flowOn(io)

    private fun readFile(f: ArchiveRecord, fromNs: Long, toNs: Long, into: MutableList<TrackRow>) {
        try {
            ParquetFile.open(store.reader(f.key), f.name).use { pf ->
                val md = pf.metadata
                val utc = UtcColumns.readEventNs(pf)?.let(LongColumn::of) ?: return
                val cell = md.leaf(CELL)?.let { LongColumn.of(pf.readColumn(it)) }
                val source = md.leaf(SOURCE)?.let { LongColumn.of(pf.readColumn(it)) }
                for (i in 0 until utc.size) {
                    if (!utc.has(i)) continue
                    val ns = utc.values[i]
                    if (ns < fromNs || ns >= toNs) continue
                    val c = if (cell != null && cell.has(i)) cell.values[i] else null
                    val s = if (source != null && source.has(i)) source.values[i].toInt() else null
                    into += TrackRow(ns, c, s)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A damaged or missing file leaves its rows out. The track never fills them in.
        }
    }

    companion object {
        /** The firmware row columns (table-and-observation-model.md). */
        const val CELL = "h3_cell_id"
        const val SOURCE = "location_source"
    }
}

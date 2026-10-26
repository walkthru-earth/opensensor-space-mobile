package space.opensensor.publicfeed

import kotlinx.datetime.LocalDate
import space.opensensor.data.history.parquet.FooterCache
import space.opensensor.data.history.parquet.LeafColumn
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetReader
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.PrimitiveColumn
import space.opensensor.data.history.parquet.RangePolicy
import space.opensensor.data.history.parquet.RangeSource
import space.opensensor.data.history.parquet.ScanRequest

/**
 * Reads a cells file through the portable `:core:parquet` reader, on Android and iOS alike. One
 * [ParquetReader.scan] reads the footer from the tail, skips row groups and pages whose `h3_index`
 * statistics miss every range, fetches the rest in as few requests as [policy] allows and filters rows
 * exactly. A [CellRow] is built only for a row that matched. The bundled sample is read from memory,
 * the live feed over HTTP ranges, with no other change.
 *
 * Two schemas are read. The hourly cells file has `updated` (a TIMESTAMP). The daily cells file (the
 * OpenAQ aggregates) has no `updated` but a `date` (DATE, INT32 days since 1970-01-01, the thing
 * local day), one row per cell and day. Each daily row keeps its [CellRow.date], and its
 * [CellRow.updatedEpochMs] is the UTC midnight after that date. [DailyCellFold] turns those rows into
 * one cell per window.
 */
class ParquetFeedReader(
    private val footerCache: FooterCache = FooterCache(),
    private val policy: RangePolicy = RangePolicy.LOCAL,
) : FeedReader {
    override suspend fun readCells(source: RangeSource, ranges: List<LongRange>?): CellsTable = readCells(source, ranges, days = null)

    /**
     * As [readCells], and for a daily file keeps only rows whose date falls in [days] (all dates when
     * null), so rows outside a window are never built. [days] does not apply to an hourly file.
     */
    suspend fun readCells(source: RangeSource, ranges: List<LongRange>?, days: ClosedRange<LocalDate>?): CellsTable {
        val file = ParquetReader.open(source, footerCache = footerCache, policy = policy)
        file.use { return read(it, ranges, days) }
    }

    private suspend fun read(file: ParquetReader, ranges: List<LongRange>?, days: ClosedRange<LocalDate>?): CellsTable {
        val meta = file.metadata
        val kv = meta.keyValueMetadata
        val properties = FeedPaths.parseDictionary(kv["dictionary"])
        val info = CellsFileInfo(
            sample = kv["sample"]?.equals("true", ignoreCase = true) == true,
            window = kv["window"],
            h3Res = kv["h3_res"]?.toIntOrNull(),
            properties = properties,
        )
        val source = file.id.uri
        val h3Leaf = meta.leaf(H3_COLUMN) ?: throw IllegalStateException("no $H3_COLUMN in $source")
        val updatedLeaf = meta.leaf(UPDATED_COLUMN)
        val dateLeaf = meta.leaf(DATE_COLUMN)?.takeIf { updatedLeaf == null }
        if (meta.leaf("devices") == null || (updatedLeaf == null && dateLeaf == null)) {
            throw IllegalStateException("devices, or updated and date, missing in $source")
        }
        if (dateLeaf != null && dateLeaf.type != ParquetType.INT32) throw IllegalStateException("date is not INT32 in $source")
        val toMs = updatedLeaf?.let(::millisFactor)
        val timeColumn = if (updatedLeaf != null) UPDATED_COLUMN else DATE_COLUMN
        val statNames = properties.associate { p -> p.id to STATS.map { s -> "${p.id}__$s" } }
        val columns = listOf(h3Leaf.name, "devices", timeColumn) + statNames.values.flatten()
        val batch = file.scan(ScanRequest(columns, filterColumn = H3_COLUMN, filterRanges = ranges ?: listOf(Long.MIN_VALUE..Long.MAX_VALUE)))
        val h3 = batch[H3_COLUMN] as? PrimitiveColumn.I64 ?: throw IllegalStateException("h3_index is not INT64 in $source")
        val devices = batch["devices"]!!
        val time = batch[timeColumn]!!
        val firstDay = days?.start?.toEpochDays()
        val lastDay = days?.endInclusive?.toEpochDays()
        val stats = statNames.mapValues { (_, names) -> names.map { batch[it] } }
        val rows = ArrayList<CellRow>(batch.rowCount)
        for (i in 0 until batch.rowCount) {
            val epochDay = if (toMs == null) time.asLong(i) ?: continue else null
            if (epochDay != null && firstDay != null && lastDay != null && epochDay !in firstDay..lastDay) continue
            val cell = LinkedHashMap<String, CellStat>()
            for ((id, cols) in stats) {
                val stat = CellStat(
                    n = cols[0]?.asLong(i)?.toInt(),
                    mean = cols[1]?.asDouble(i),
                    min = cols[2]?.asDouble(i),
                    max = cols[3]?.asDouble(i),
                )
                if (stat.mean != null || stat.n != null) cell[id] = stat
            }
            rows += if (epochDay == null) {
                CellRow(
                    h3 = h3.values[i],
                    devices = devices.asLong(i)?.toInt() ?: 0,
                    updatedEpochMs = time.asLong(i)?.let(toMs!!) ?: 0L,
                    stats = cell,
                )
            } else {
                CellRow(
                    h3 = h3.values[i],
                    devices = devices.asLong(i)?.toInt() ?: 0,
                    updatedEpochMs = (epochDay + 1) * DAY_MS,
                    stats = cell,
                    date = LocalDate.fromEpochDays(epochDay),
                )
            }
        }
        return CellsTable(info, rows, batch.rowGroupsRead, batch.rowGroupsTotal, batch.pagesRead, batch.pagesTotal)
    }

    private fun millisFactor(leaf: LeafColumn): (Long) -> Long = when ((leaf.logicalType as? ParquetLogicalType.Timestamp)?.unit) {
        ParquetLogicalType.TimeUnit.MICROS -> { v -> v / 1_000 }
        ParquetLogicalType.TimeUnit.NANOS -> { v -> v / 1_000_000 }
        else -> { v -> v }
    }

    private companion object {
        const val H3_COLUMN = "h3_index"
        const val UPDATED_COLUMN = "updated"
        const val DATE_COLUMN = "date"
        const val DAY_MS = 86_400_000L
        val STATS = listOf("n", "mean", "min", "max")
    }
}

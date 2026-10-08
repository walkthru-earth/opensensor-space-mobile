package earth.walkthru.aqsync.publicfeed

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.LeafColumn
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.SuspendByteReader
import earth.walkthru.aqsync.data.history.parquet.SuspendParquetFile

/**
 * Reads a cells file through the portable `:core:parquet` reader, on Android and iOS alike. The
 * footer is read first, row groups whose `h3_index` statistics miss every range are skipped, then
 * one range read per needed column chunk, and rows are filtered exactly. Over the bundled sample
 * the reads are copies from memory. Over HTTP they become range requests, with no other change.
 */
class ParquetFeedReader : FeedReader {
    override suspend fun readCells(reader: SuspendByteReader, source: String, ranges: List<LongRange>?): CellsTable {
        val file = SuspendParquetFile.open(reader, source)
        val meta = file.metadata
        val kv = meta.keyValueMetadata
        val properties = FeedPaths.parseDictionary(kv["dictionary"])
        val info = CellsFileInfo(
            sample = kv["sample"]?.equals("true", ignoreCase = true) == true,
            window = kv["window"],
            h3Res = kv["h3_res"]?.toIntOrNull(),
            properties = properties,
        )
        val h3Leaf = file.leaf(H3_COLUMN)
        val devicesLeaf = file.leaf("devices")
        val updatedLeaf = file.leaf("updated")
        val toMs = millisFactor(updatedLeaf)
        val statLeaves = properties.associate { p -> p.id to STATS.map { s -> meta.leaf("${p.id}__$s") } }
        val groups = if (ranges == null) meta.rowGroups.indices.toList() else meta.rowGroupsOverlapping(H3_COLUMN, ranges)
        val rows = ArrayList<CellRow>()
        for (rg in groups) {
            val h3 = file.readColumn(h3Leaf, rg) as? ColumnValues.Int64
                ?: throw IllegalStateException("h3_index is not INT64 in $source")
            val keep = (0 until h3.size).filter { i -> !h3.isNull(i) && (ranges == null || H3Index.inRanges(h3.values[i], ranges)) }
            if (keep.isEmpty()) continue
            val devices = file.readColumn(devicesLeaf, rg)
            val updated = file.readColumn(updatedLeaf, rg)
            val columns = statLeaves.mapValues { (_, leaves) -> leaves.map { leaf -> leaf?.let { file.readColumn(it, rg) } } }
            for (i in keep) {
                val stats = LinkedHashMap<String, CellStat>()
                for ((id, cols) in columns) {
                    val stat = CellStat(
                        n = cols[0]?.asLong(i)?.toInt(),
                        mean = cols[1]?.asDouble(i),
                        min = cols[2]?.asDouble(i),
                        max = cols[3]?.asDouble(i),
                    )
                    if (stat.mean != null || stat.n != null) stats[id] = stat
                }
                rows += CellRow(
                    h3 = h3.values[i],
                    devices = devices.asLong(i)?.toInt() ?: 0,
                    updatedEpochMs = updated.asLong(i)?.let(toMs) ?: 0L,
                    stats = stats,
                )
            }
        }
        return CellsTable(info, rows, groups.size, meta.rowGroups.size)
    }

    private fun millisFactor(leaf: LeafColumn): (Long) -> Long = when ((leaf.logicalType as? ParquetLogicalType.Timestamp)?.unit) {
        ParquetLogicalType.TimeUnit.MICROS -> { v -> v / 1_000 }
        ParquetLogicalType.TimeUnit.NANOS -> { v -> v / 1_000_000 }
        else -> { v -> v }
    }

    private companion object {
        const val H3_COLUMN = "h3_index"
        val STATS = listOf("n", "mean", "min", "max")
    }
}

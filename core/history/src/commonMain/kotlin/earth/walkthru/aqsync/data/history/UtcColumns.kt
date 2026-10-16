package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.LeafColumn
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFileMetadata
import earth.walkthru.aqsync.data.history.parquet.ParquetFormatException
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType.TimeUnit
import earth.walkthru.aqsync.data.history.parquet.ParquetType

/**
 * The UTC instant columns of a device file, read in nanoseconds whatever unit the file stores.
 *
 * CoreS3 v6.12 and Waveshare v2.6 write `event_time_utc_us` and `clock_anchor_utc_us` as
 * `TIMESTAMP(MICROS, isAdjustedToUTC=true)`. Older files on the phone carry `event_time_utc_ns` and
 * `clock_anchor_utc_ns` as `TIMESTAMP(NANOS, UTC)`. The name only finds the column. The scale comes
 * from the Parquet logical type unit. The one exception is the first CoreS3 files (schema v1/v2),
 * which left their `*_utc_ns` columns unannotated, so an unannotated INT64 under one of the two old
 * names reads as nanoseconds. The app timeline, the index and the tiers stay in nanoseconds.
 */
object UtcColumns {
    const val EVENT_US = "event_time_utc_us"
    const val EVENT_NS = "event_time_utc_ns"
    const val ANCHOR_US = "clock_anchor_utc_us"
    const val ANCHOR_NS = "clock_anchor_utc_ns"

    /** Each older column name to the current one, so a derived file never holds both. */
    val RENAMED: Map<String, String> = mapOf(EVENT_NS to EVENT_US, ANCHOR_NS to ANCHOR_US)

    /** Every name a UTC instant column has had. */
    val NAMES: Set<String> = setOf(EVENT_US, EVENT_NS, ANCHOR_US, ANCHOR_NS)

    private const val NS_PER_US = 1_000L

    /** The row event instant leaf, the current name first. */
    fun eventLeaf(md: ParquetFileMetadata): LeafColumn? = md.leaf(EVENT_US) ?: md.leaf(EVENT_NS)

    /** The name [name] has in files the phone writes. */
    fun canonical(name: String): String = RENAMED[name] ?: name

    /**
     * Nanoseconds per stored unit of [leaf], from its `TIMESTAMP` unit. Throws when the leaf is not a
     * UTC instant this app reads, so a foreign layout is refused instead of misdated.
     */
    fun nanosPerUnit(leaf: LeafColumn): Long {
        if (leaf.type != ParquetType.INT64) throw ParquetFormatException("'${leaf.name}' is ${leaf.type}, not an INT64 UTC timestamp")
        return when (leaf.timestampUtcUnit) {
            TimeUnit.NANOS -> 1L
            TimeUnit.MICROS -> NS_PER_US
            TimeUnit.MILLIS -> 1_000_000L
            null -> if (leaf.logicalType == null && leaf.name in RENAMED) 1L else throw ParquetFormatException("'${leaf.name}' is not a UTC TIMESTAMP")
            TimeUnit.UNKNOWN -> throw ParquetFormatException("'${leaf.name}' has an unknown TIMESTAMP unit")
        }
    }

    /** True when [leaf] holds a UTC instant under one of the known names. */
    fun isUtcInstant(leaf: LeafColumn): Boolean = leaf.name in NAMES

    /** The event instant of every row in nanoseconds, or null when the file has no event time column. */
    fun readEventNs(file: ParquetFile): ColumnValues.Int64? = eventLeaf(file.metadata)?.let { readNs(file, it) }

    /** [leaf] decoded and scaled to nanoseconds. */
    fun readNs(file: ParquetFile, leaf: LeafColumn): ColumnValues.Int64 = toNs(file.readColumn(leaf), nanosPerUnit(leaf))

    /**
     * [column] multiplied by [scale]. A value whose nanoseconds would not fit a Long (past 2262) is
     * null, so a bad timestamp never wraps into an apparently valid UTC partition.
     */
    fun toNs(column: ColumnValues, scale: Long): ColumnValues.Int64 {
        val source = column as? ColumnValues.Int64 ?: throw ParquetFormatException("UTC column is ${column::class.simpleName}, not INT64")
        if (scale == 1L) return source
        val limit = Long.MAX_VALUE / scale
        val out = LongArray(source.size)
        var valid: BooleanArray? = source.valid?.copyOf()
        for (i in 0 until source.size) {
            if (source.isNull(i)) continue
            val v = source.values[i]
            if (v > limit || v < -limit) {
                if (valid == null) valid = BooleanArray(source.size) { true }
                valid[i] = false
            } else {
                out[i] = v * scale
            }
        }
        return ColumnValues.Int64(out, valid)
    }

    /** [column] of a [leaf] UTC instant as microseconds, the unit a derived file stores. */
    fun toMicros(column: ColumnValues, leaf: LeafColumn): ColumnValues.Int64 {
        val source = column as? ColumnValues.Int64 ?: throw ParquetFormatException("'${leaf.name}' is ${column::class.simpleName}, not INT64")
        val scale = nanosPerUnit(leaf)
        if (scale == NS_PER_US) return source
        val out = LongArray(source.size)
        var valid: BooleanArray? = source.valid?.copyOf()
        val limit = Long.MAX_VALUE / scale
        for (i in 0 until source.size) {
            if (source.isNull(i)) continue
            val v = source.values[i]
            if (v > limit || v < -limit) {
                if (valid == null) valid = BooleanArray(source.size) { true }
                valid[i] = false
            } else {
                out[i] = nsToUs(v * scale)
            }
        }
        return ColumnValues.Int64(out, valid)
    }

    /** Whole microseconds at or before [ns]. */
    fun nsToUs(ns: Long): Long = ns.floorDiv(NS_PER_US)

    /** Whole microseconds at or after [ns], the exclusive end of a half-open range. */
    fun nsToUsCeil(ns: Long): Long = -(-ns).floorDiv(NS_PER_US)
}

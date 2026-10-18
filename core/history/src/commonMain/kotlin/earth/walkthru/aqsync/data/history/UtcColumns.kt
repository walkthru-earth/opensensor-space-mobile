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

    /**
     * True for a device file in the old layout, the one Settings can remove from the phone. Its row
     * event column is `event_time_utc_ns`, an INT64 that is either `TIMESTAMP(NANOS, UTC)` or carries
     * no annotation (schema v1/v2), the file has no `event_time_utc_us`, and no leaf has a field id.
     * A file without any event time column is never old, so it is always kept. Purpose plays no part.
     */
    fun isLegacyLayout(md: ParquetFileMetadata): Boolean {
        if (md.leaf(EVENT_US) != null) return false
        val leaf = md.leaf(EVENT_NS) ?: return false
        if (leaf.type != ParquetType.INT64) return false
        val nanos = leaf.timestampUtcUnit == TimeUnit.NANOS || leaf.logicalType == null
        return nanos && md.leaves.none { it.fieldId != null }
    }

    /** True when [leaf] holds a UTC instant under one of the known names. */
    fun isUtcInstant(leaf: LeafColumn): Boolean = leaf.name in NAMES

    /** The event instant of every row in nanoseconds, or null when the file has no event time column. */
    fun readEventNs(file: ParquetFile): ColumnValues.Int64? = eventLeaf(file.metadata)?.let { readNs(file, it) }

    /** [leaf] decoded and scaled to nanoseconds. */
    fun readNs(file: ParquetFile, leaf: LeafColumn): ColumnValues.Int64 = toNs(file.readColumn(leaf), nanosPerUnit(leaf))

    /**
     * The one clamp rule for every unit. A stored [value] is a usable instant only when it is a whole
     * number of nanoseconds that fits a Long, that is between about 1677 and 2262. Every reader
     * ([toNs], [toMicros], [scaleToNs]) applies it, so MICROS, MILLIS and NANOS columns agree on
     * which rows have a time.
     */
    fun fitsNs(value: Long, scale: Long): Boolean = scale == 1L || value in -(Long.MAX_VALUE / scale)..(Long.MAX_VALUE / scale)

    /**
     * [column] multiplied by [scale]. A value that fails [fitsNs] is null, so a bad timestamp never
     * wraps into an apparently valid UTC partition.
     */
    fun toNs(column: ColumnValues, scale: Long): ColumnValues.Int64 {
        val source = column as? ColumnValues.Int64 ?: throw ParquetFormatException("UTC column is ${column::class.simpleName}, not INT64")
        if (scale == 1L) return source
        return mapFitting(source, scale) { it * scale }
    }

    /**
     * [column] of a [leaf] UTC instant as microseconds, the unit a derived file stores. The same
     * [fitsNs] rule applies to a MICROS column too, so a row the timeline drops is dropped here as well.
     */
    fun toMicros(column: ColumnValues, leaf: LeafColumn): ColumnValues.Int64 {
        val source = column as? ColumnValues.Int64 ?: throw ParquetFormatException("'${leaf.name}' is ${column::class.simpleName}, not INT64")
        val scale = nanosPerUnit(leaf)
        return mapFitting(source, scale) { if (scale == NS_PER_US) it else nsToUs(it * scale) }
    }

    /** [source] through [map], with every value that fails [fitsNs] for [scale] turned null. */
    private inline fun mapFitting(source: ColumnValues.Int64, scale: Long, map: (Long) -> Long): ColumnValues.Int64 {
        val out = LongArray(source.size)
        var valid: BooleanArray? = source.valid?.copyOf()
        for (i in 0 until source.size) {
            if (source.isNull(i)) continue
            val v = source.values[i]
            if (fitsNs(v, scale)) {
                out[i] = map(v)
            } else {
                if (valid == null) valid = BooleanArray(source.size) { true }
                valid[i] = false
            }
        }
        return ColumnValues.Int64(out, valid)
    }

    /** One [value] in a unit of [scale] nanoseconds, as nanoseconds. Null when it fails [fitsNs], never wrapped. */
    fun scaleToNs(value: Long, scale: Long): Long? = if (fitsNs(value, scale)) value * scale else null

    /** Whole units of [scale] nanoseconds at or before [ns]. */
    fun nsToUnit(ns: Long, scale: Long): Long = ns.floorDiv(scale)

    /**
     * Whole units of [scale] nanoseconds at or after [ns], the exclusive end of a half-open range.
     * Built on the floor so `Long.MIN_VALUE` never wraps through negation.
     */
    fun nsToUnitCeil(ns: Long, scale: Long): Long {
        val floor = ns.floorDiv(scale)
        return if (floor * scale == ns) floor else floor + 1
    }

    /** Whole microseconds at or before [ns]. */
    fun nsToUs(ns: Long): Long = nsToUnit(ns, NS_PER_US)

    /** Whole microseconds at or after [ns], the exclusive end of a half-open range. */
    fun nsToUsCeil(ns: Long): Long = nsToUnitCeil(ns, NS_PER_US)
}

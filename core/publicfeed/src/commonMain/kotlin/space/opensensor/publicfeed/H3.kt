package space.opensensor.publicfeed

/** A WGS 84 position in degrees. Used for H3 geometry only, never stored or sent. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * The H3 operations the phone needs, because the public feed carries cell indexes and no geometry.
 * Android and the JVM bind `com.uber:h3`. iOS binds the H3 C library through cinterop.
 * Everything that is pure bit arithmetic on an index lives in [H3Index] instead.
 */
interface H3Grid {
    /** False when this platform has no H3 binding yet. Callers then show an empty map. */
    val available: Boolean get() = true

    fun latLngToCell(lat: Double, lng: Double, res: Int): Long

    fun cellToLatLng(cell: Long): LatLng

    /** The cell outline, counter-clockwise, without repeating the first vertex. */
    fun cellToBoundary(cell: Long): List<LatLng>

    /** Cells whose centres fall inside the polygon [ring] (no holes). */
    fun polygonToCells(ring: List<LatLng>, res: Int): List<Long>

    /**
     * The union of [cells], all at one resolution, as polygons. Each polygon is its outer loop first,
     * then its holes, and no loop repeats its first vertex. Cells that share an edge merge into one
     * polygon, so a polygon only touches another at a vertex. Empty for no cells.
     */
    fun cellsToMultiPolygon(cells: Collection<Long>): List<List<List<LatLng>>> =
        throw UnsupportedOperationException("this H3 binding has no cell union")
}

/** The platform H3 binding. */
expect fun platformH3Grid(): H3Grid

/**
 * Pure bit arithmetic on 64-bit H3 cell indexes (H3 v4 layout). No library needed, so the
 * descendant ranges used for row group pruning are identical on every platform.
 *
 * Layout from the high bit: 1 reserved bit, 4 mode bits, 3 reserved bits, 4 resolution bits,
 * 7 base cell bits, then 15 digits of 3 bits for resolutions 1 to 15. Unused digits are 7.
 */
object H3Index {
    const val MAX_RES = 15
    private const val RES_OFFSET = 52
    private const val RES_MASK = 0xFL shl RES_OFFSET
    private const val DIGIT_BITS = 3

    fun resolution(cell: Long): Int = ((cell and RES_MASK) ushr RES_OFFSET).toInt()

    private fun digitOffset(res: Int): Int = (MAX_RES - res) * DIGIT_BITS

    private fun withResolution(cell: Long, res: Int): Long = (cell and RES_MASK.inv()) or (res.toLong() shl RES_OFFSET)

    private fun withDigit(cell: Long, res: Int, digit: Long): Long {
        val offset = digitOffset(res)
        return (cell and (7L shl offset).inv()) or (digit shl offset)
    }

    /** The ancestor of [cell] at [res]. [res] must not be finer than the cell. */
    fun parent(cell: Long, res: Int): Long {
        val own = resolution(cell)
        require(res in 0..own) { "parent resolution $res is finer than the cell resolution $own" }
        var out = withResolution(cell, res)
        for (r in res + 1..own) out = withDigit(out, r, 7)
        return out
    }

    /**
     * Every descendant of [cell] at [childRes] has an index in the returned range, and so does
     * nothing else at that resolution. Indexes at one resolution sort by base cell, then digits,
     * so a descendant set is one contiguous range (pentagons only leave gaps in it).
     */
    fun descendantRange(cell: Long, childRes: Int): LongRange {
        val own = resolution(cell)
        require(childRes in own..MAX_RES) { "child resolution $childRes is coarser than the cell resolution $own" }
        var low = withResolution(cell, childRes)
        var high = low
        for (r in own + 1..childRes) {
            low = withDigit(low, r, 0)
            high = withDigit(high, r, 6)
        }
        return low..high
    }

    /** Sorts and merges touching or overlapping ranges. */
    fun merge(ranges: Collection<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<LongRange>(sorted.size)
        var start = sorted[0].first
        var end = sorted[0].last
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            if (r.first <= end + 1) {
                if (r.last > end) end = r.last
            } else {
                out += start..end
                start = r.first
                end = r.last
            }
        }
        out += start..end
        return out
    }

    /** True when [value] falls in one of the sorted, merged [ranges]. */
    fun inRanges(value: Long, ranges: List<LongRange>): Boolean {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                value < r.first -> hi = mid - 1
                value > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** True when the closed interval [min, max] overlaps one of the sorted, merged [ranges]. */
    fun overlaps(min: Long, max: Long, ranges: List<LongRange>): Boolean {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                max < r.first -> hi = mid - 1
                min > r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The lowercase hex form Python `h3` and the website print. */
    fun toHex(cell: Long): String = cell.toULong().toString(16)

    fun fromHex(hex: String): Long = hex.toULong(16).toLong()
}

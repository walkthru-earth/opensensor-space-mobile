package space.opensensor.model

/**
 * The H3 cell checks every module shares, as pure bit arithmetic on the 64-bit H3 v4 index. It needs
 * no H3 library, so the protocol mirror, the sync rules and `H3Index` in `:core:publicfeed` answer
 * alike on Android and iOS. `H3IsCellTest` holds [isCell] to the H3 library.
 *
 * Layout from the high bit. 1 reserved bit, 4 mode bits, 3 reserved bits, 4 resolution bits,
 * 7 base cell bits, then 15 digits of 3 bits for resolutions 1 to 15. Unused digits are 7.
 */
object H3Cell {
    /** The one resolution a Thing publishes and the board stores (owner decision OD-2026-11-04-B). */
    const val RESOLUTION = 8

    const val MAX_RES = 15
    private const val RES_OFFSET = 52
    private const val MODE_OFFSET = 59
    private const val BASE_OFFSET = 45
    private const val BASE_CELLS = 122L
    private const val HEX_LENGTH = 15

    /** The 12 base cells that are pentagons. Their deleted K axis subsequence has no cell. */
    private val PENTAGON_BASE_CELLS = setOf(4L, 14L, 24L, 38L, 49L, 58L, 63L, 72L, 83L, 97L, 107L, 117L)
    private const val K_AXES_DIGIT = 1L

    fun resolution(cell: Long): Int = ((cell ushr RES_OFFSET) and 0xFL).toInt()

    /**
     * True when [cell] is an H3 cell index. Its mode is 1, its base cell is one of the 122, each digit
     * down to its resolution is 0 to 6 and every finer digit is 7. Under a pentagon base cell the first
     * digit that is not 0 is never 1, the deleted K axis, as in the H3 library's `isValidCell`.
     */
    fun isCell(cell: Long): Boolean {
        if (cell < 0 || ((cell ushr MODE_OFFSET) and 0xFL) != 1L || ((cell ushr (RES_OFFSET + 4)) and 0x7L) != 0L) return false
        val base = (cell ushr BASE_OFFSET) and 0x7FL
        if (base >= BASE_CELLS) return false
        val res = resolution(cell)
        val digits = (1..MAX_RES).map { r -> (cell ushr ((MAX_RES - r) * 3)) and 7L }
        if (!digits.withIndex().all { (i, digit) -> if (i < res) digit != 7L else digit == 7L }) return false
        return base !in PENTAGON_BASE_CELLS || digits.take(res).firstOrNull { it != 0L } != K_AXES_DIGIT
    }

    /**
     * The index of [text] when it is the canonical form the board and the public feed use, 15
     * lowercase hex digits naming an H3 cell, at [resolution] when one is given. Null otherwise.
     */
    fun parseHex(text: String, resolution: Int? = null): Long? {
        if (text.length != HEX_LENGTH || text.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val cell = text.toULong(16).toLong()
        if (!isCell(cell)) return null
        return if (resolution == null || resolution(cell) == resolution) cell else null
    }

    /** True when [text] is a canonical H3 cell hex, at [resolution] when one is given. */
    fun isCellHex(text: String, resolution: Int? = null): Boolean = parseHex(text, resolution) != null
}

package earth.walkthru.aqsync.data.history.parquet

/**
 * A streaming reader of DELTA_BINARY_PACKED values on an INT32 or INT64 column. The page starts with a
 * header (block size, miniblocks per block, value count, then the first value as zigzag ULEB128). Each
 * block follows with its minimum delta (zigzag), one bit-width byte per miniblock and the miniblocks,
 * bit packed LSB first. Every value comes back as 64 bits, which an INT32 column truncates.
 *
 * Deltas are summed in 64 bits and a miniblock may be up to 64 bits wide on either type. A writer that
 * wraps INT32 deltas at 32 bits (PyArrow) and one that takes them in 64 bits (DuckDB, whose miniblocks
 * can then be 33 bits wide) decode to the same INT32 values. One instance is reset per page, so decoding
 * allocates nothing once [widths] has grown.
 */
internal class DeltaBinaryPackedDecoder {
    private var data = ByteArray(0)
    private var limit = 0
    private var remaining = 0
    private var started = false
    private var previous = 0L
    private var minDelta = 0L
    private var miniblocks = 0
    private var valuesPerMiniblock = 0
    private var widths = IntArray(0)
    private var miniblock = 0
    private var miniblockStart = 0L
    private var miniblockEnd = 0L
    private var bitWidth = 0
    private var inMiniblock = 0
    private var column = ""
    private var source = ""

    fun reset(data: ByteArray, from: Int, limit: Int, column: String, source: String): DeltaBinaryPackedDecoder {
        this.data = data
        this.limit = limit
        this.column = column
        this.source = source
        started = false
        remaining = 0
        if (from >= limit) return this // no values: the page is all nulls
        var pos = from
        fun uleb(what: String): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (pos >= limit) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED $what truncated ($source)")
                val b = data[pos++].toInt() and 0xFF
                if (shift == 63 && b > 1) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED $what overflows 64 bits ($source)")
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 63) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED $what varint too long ($source)")
            }
        }
        val blockSize = uleb("block size")
        val count = uleb("miniblock count")
        val total = uleb("value count")
        if (blockSize <= 0 || blockSize > MAX_BLOCK || blockSize % 128 != 0L) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED block size $blockSize ($source)")
        if (count <= 0 || count > blockSize || blockSize % count != 0L || (blockSize / count) % 32 != 0L) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED $count miniblocks in a block of $blockSize ($source)")
        if (total < 0 || total > ParquetLimits.MAX_ROWS) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED value count $total ($source)")
        previous = zigzag(uleb("first value"))
        miniblocks = count.toInt()
        valuesPerMiniblock = (blockSize / count).toInt()
        if (widths.size < miniblocks) widths = IntArray(miniblocks)
        remaining = total.toInt()
        // Start as if the last miniblock of a previous block had just ended at [pos].
        miniblock = miniblocks - 1
        inMiniblock = valuesPerMiniblock
        miniblockEnd = pos.toLong()
        return this
    }

    /** The next value as 64 bits. */
    fun next(): Long {
        if (remaining == 0) throw ParquetFormatException("'$column': more values than the DELTA_BINARY_PACKED header holds ($source)")
        remaining--
        if (!started) {
            started = true
            return previous
        }
        if (inMiniblock == valuesPerMiniblock) nextMiniblock()
        val bit = inMiniblock.toLong() * bitWidth
        inMiniblock++
        previous += minDelta + readBits(miniblockStart + (bit ushr 3), (bit and 7).toInt())
        return previous
    }

    private fun nextMiniblock() {
        miniblockStart = miniblockEnd
        if (++miniblock == miniblocks) readBlockHeader()
        val width = widths[miniblock]
        // Widths of miniblocks past the last value may be anything, so each is checked only once reached.
        if (width > 64) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED bit width $width ($source)")
        bitWidth = width
        miniblockEnd = miniblockStart + valuesPerMiniblock.toLong() * width / 8
        inMiniblock = 0
    }

    private fun readBlockHeader() {
        var pos = miniblockStart
        var value = 0L
        var shift = 0
        while (true) {
            if (pos >= limit) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED block header truncated ($source)")
            val b = data[(pos++).toInt()].toInt() and 0xFF
            if (shift == 63 && b > 1) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED minimum delta overflows 64 bits ($source)")
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED minimum delta varint too long ($source)")
        }
        minDelta = zigzag(value)
        if (pos + miniblocks > limit) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED bit widths truncated ($source)")
        for (m in 0 until miniblocks) widths[m] = data[(pos++).toInt()].toInt() and 0xFF
        miniblock = 0
        miniblockStart = pos
    }

    /** [bitWidth] bits from bit [shift] of the byte at [at]. A miniblock is read only as far as its values go. */
    private fun readBits(at: Long, shift: Int): Long {
        if (bitWidth == 0) return 0L
        val bytes = (shift + bitWidth + 7) ushr 3
        if (at + bytes > limit) throw ParquetFormatException("'$column': DELTA_BINARY_PACKED miniblock truncated ($source)")
        val p = at.toInt()
        var v = 0L
        for (k in 0 until minOf(bytes, 8)) v = v or ((data[p + k].toLong() and 0xFF) shl (8 * k))
        v = v ushr shift
        if (bytes == 9) v = v or ((data[p + 8].toLong() and 0xFF) shl (64 - shift))
        return if (bitWidth == 64) v else v and ((1L shl bitWidth) - 1)
    }

    private fun zigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    private companion object {
        /** Values in one block. Writers use 128. */
        const val MAX_BLOCK = 1L shl 20
    }
}

/**
 * A reader of BYTE_STREAM_SPLIT values: byte k of value j sits at `k * count + j`, one stream per byte
 * of the value. Works for any fixed width, 4 or 8 bytes here (INT32, INT64, FLOAT, DOUBLE), and returns
 * each value's bits little endian.
 */
internal class ByteStreamSplitDecoder {
    private var data = ByteArray(0)
    private var from = 0
    private var width = 0
    private var count = 0
    private var index = 0
    private var column = ""
    private var source = ""

    fun reset(data: ByteArray, from: Int, limit: Int, width: Int, column: String, source: String): ByteStreamSplitDecoder {
        val size = limit - from
        if (size < 0 || size % width != 0) throw ParquetFormatException("'$column': BYTE_STREAM_SPLIT data of $size bytes is not a whole number of $width byte values ($source)")
        this.data = data
        this.from = from
        this.width = width
        this.count = size / width
        this.index = 0
        this.column = column
        this.source = source
        return this
    }

    fun next(): Long {
        if (index >= count) throw ParquetFormatException("'$column': more values than the BYTE_STREAM_SPLIT data holds ($source)")
        var v = 0L
        var at = from + index
        for (k in 0 until width) {
            v = v or ((data[at].toLong() and 0xFF) shl (8 * k))
            at += count
        }
        index++
        return v
    }
}

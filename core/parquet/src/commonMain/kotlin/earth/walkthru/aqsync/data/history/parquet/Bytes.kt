package earth.walkthru.aqsync.data.history.parquet

/**
 * Little-endian reads over a window `[start, limit)` of a byte array with a moving [pos]. The common
 * replacement for the `java.nio.ByteBuffer` the reader used on the JVM: no copies, absolute and
 * relative reads, and an [IndexOutOfBoundsException] (a [RuntimeException]) on truncation, which the
 * decoders turn into a [ParquetFormatException] naming the column.
 */
internal class ByteCursor(val data: ByteArray, val start: Int = 0, val limit: Int = data.size) {
    var pos: Int = start

    init {
        if (start < 0 || limit > data.size || start > limit) throw IndexOutOfBoundsException("window [$start, $limit) outside ${data.size} bytes")
    }

    val remaining: Int get() = limit - pos
    val hasRemaining: Boolean get() = pos < limit

    /** Bytes in the window, the old `ByteBuffer.limit()` of a slice. */
    val size: Int get() = limit - start

    fun get(): Byte {
        if (pos >= limit) throw IndexOutOfBoundsException("read past the end ($limit)")
        return data[pos++]
    }

    fun getU8(): Int = get().toInt() and 0xFF

    fun getInt(): Int {
        val v = intAt(pos)
        pos += 4
        return v
    }

    fun getLong(): Long {
        val v = longAt(pos)
        pos += 8
        return v
    }

    fun getDouble(): Double = Double.fromBits(getLong())

    /** Copies [length] bytes into [dst] at [dstOffset] and advances. */
    fun get(dst: ByteArray, dstOffset: Int = 0, length: Int = dst.size) {
        check(pos, length)
        data.copyInto(dst, dstOffset, pos, pos + length)
        pos += length
    }

    fun skip(n: Int) {
        check(pos, n)
        pos += n
    }

    /** Absolute little-endian i32 at array index [at] (inside the window). */
    fun intAt(at: Int): Int {
        check(at, 4)
        return leInt(data, at)
    }

    fun longAt(at: Int): Long {
        check(at, 8)
        return leLong(data, at)
    }

    /** A new cursor over `[from, from + length)` of the same array (indices are array indices). */
    fun window(from: Int, length: Int): ByteCursor {
        check(from, length)
        return ByteCursor(data, from, from + length)
    }

    private fun check(at: Int, n: Int) {
        if (n < 0 || at < start || at.toLong() + n > limit) throw IndexOutOfBoundsException("read of $n bytes at $at outside [$start, $limit)")
    }
}

internal fun leInt(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

internal fun leLong(b: ByteArray, at: Int): Long =
    (leInt(b, at).toLong() and 0xFFFF_FFFFL) or (leInt(b, at + 4).toLong() shl 32)

/** A growable byte buffer: the common stand-in for `ByteArrayOutputStream`. */
internal class ByteOutput(initial: Int = 64) {
    private var buf = ByteArray(maxOf(initial, 16))
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun write(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        ensure(length)
        bytes.copyInto(buf, size, offset, offset + length)
        size += length
    }

    fun writeIntLe(v: Int) {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
    }

    fun writeLongLe(v: Long) {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal fun le32(v: Int): ByteArray = ByteOutput(4).apply { writeIntLe(v) }.toByteArray()
internal fun le64(v: Long): ByteArray = ByteOutput(8).apply { writeLongLe(v) }.toByteArray()

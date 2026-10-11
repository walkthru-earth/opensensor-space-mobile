package earth.walkthru.aqsync.data.history.parquet

/**
 * Random access to the bytes of one Parquet file: the only thing [ParquetFile] needs from storage.
 *
 * Implementations: [ByteArrayReader] (bytes already in memory), the memory-mapped file of each platform
 * behind [ParquetFile.open] (`FileChannel.map` on the JVM, mapped `NSData` on iOS), and anything a caller
 * writes over another store. Reads are absolute, so one instance serves concurrent readers when the
 * implementation is thread-safe; the built-in ones are.
 */
interface ByteReader {
    /** Total length of the file in bytes. */
    val size: Long

    /**
     * Exactly [length] bytes starting at [offset], as a new array the caller owns.
     * @throws IndexOutOfBoundsException when `[offset, offset + length)` is not inside `[0, size)`.
     */
    fun read(offset: Long, length: Int): ByteArray

    /** The same bytes as a [ByteView]. Readers over memory return a view of it without copying. */
    fun view(offset: Long, length: Int): ByteView = ByteView(read(offset, length))
}

/** A [ByteReader] over bytes already in memory. [read] copies the requested range, [view] shares it. */
class ByteArrayReader(private val bytes: ByteArray) : ByteReader {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length, size)
        val from = offset.toInt()
        return bytes.copyOfRange(from, from + length)
    }

    override fun view(offset: Long, length: Int): ByteView {
        checkRange(offset, length, size)
        return ByteView(bytes, offset.toInt(), length)
    }
}

/** Bounds check shared by the readers: throws [IndexOutOfBoundsException] for a range outside `[0, size)`. */
fun checkRange(offset: Long, length: Int, size: Long) {
    if (offset < 0 || length < 0 || offset + length > size) throw IndexOutOfBoundsException("range [$offset, ${offset + length}) outside $size bytes")
}

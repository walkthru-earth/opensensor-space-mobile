package earth.walkthru.aqsync.data.protocol

/** iOS CRC-32 through the shared table-driven implementation. */
actual class Crc32 actual constructor() {
    private val delegate = PortableCrc32()

    actual fun update(bytes: ByteArray, offset: Int, length: Int) = delegate.update(bytes, offset, length)

    actual fun update(bytes: ByteArray) = delegate.update(bytes, 0, bytes.size)

    actual fun reset() = delegate.reset()

    actual val value: Long get() = delegate.value
}

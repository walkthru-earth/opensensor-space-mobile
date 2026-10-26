package space.opensensor.data.protocol

import java.util.zip.CRC32

/** JVM CRC-32 through `java.util.zip.CRC32`, which HotSpot and ART accelerate. */
actual class Crc32 actual constructor() {
    private val delegate = CRC32()

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        checkRange(bytes.size, offset, length)
        delegate.update(bytes, offset, length)
    }

    actual fun update(bytes: ByteArray) = delegate.update(bytes)

    actual fun reset() = delegate.reset()

    actual val value: Long get() = delegate.value
}

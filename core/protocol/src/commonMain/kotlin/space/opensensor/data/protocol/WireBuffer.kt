package space.opensensor.data.protocol

/** Bounds-checked little-endian cursor for the firmware's wire format, without JVM APIs. */
internal class WireBuffer private constructor(private val bytes: ByteArray) {
    private var position = 0

    fun remaining(): Int = bytes.size - position

    fun get(): Byte {
        if (remaining() < 1) throw WireUnderflowException()
        return bytes[position++]
    }

    fun get(destination: ByteArray) {
        if (remaining() < destination.size) throw WireUnderflowException()
        bytes.copyInto(destination, 0, position, position + destination.size)
        position += destination.size
    }

    private fun unsigned(size: Int): Long {
        // One bounds check for the whole field, then direct indexing.
        if (remaining() < size) throw WireUnderflowException()
        var value = 0L
        for (shift in 0 until size) value = value or ((bytes[position + shift].toLong() and 0xFF) shl (shift * 8))
        position += size
        return value
    }

    val short: Short get() = unsigned(2).toShort()
    val int: Int get() = unsigned(4).toInt()
    val long: Long get() = unsigned(8)

    fun put(value: Byte): WireBuffer {
        check(remaining() >= 1) { "wire buffer overflow" }
        bytes[position++] = value
        return this
    }

    fun put(value: ByteArray): WireBuffer {
        check(remaining() >= value.size) { "wire buffer overflow" }
        value.copyInto(bytes, position)
        position += value.size
        return this
    }

    private fun putInteger(value: Long, size: Int): WireBuffer {
        repeat(size) { shift -> put((value ushr (shift * 8)).toByte()) }
        return this
    }

    fun putShort(value: Short): WireBuffer = putInteger(value.toLong(), 2)
    fun putInt(value: Int): WireBuffer = putInteger(value.toLong(), 4)
    fun putLong(value: Long): WireBuffer = putInteger(value, 8)
    fun array(): ByteArray = bytes

    companion object {
        fun allocate(size: Int): WireBuffer = WireBuffer(ByteArray(size))
        fun wrap(bytes: ByteArray): WireBuffer = WireBuffer(bytes)
    }
}

internal class WireUnderflowException : Exception()

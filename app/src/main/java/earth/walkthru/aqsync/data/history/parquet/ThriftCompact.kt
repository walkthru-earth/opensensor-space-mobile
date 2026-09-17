package earth.walkthru.aqsync.data.history.parquet

import java.nio.ByteBuffer

/**
 * Minimal reader for the Thrift Compact Protocol, which is what Parquet uses for its footer and
 * page headers. Only what a *reader* of our files needs: field headers, varints, zig-zag integers,
 * binaries, lists and structs, plus skipping of anything we do not model so a newer writer never
 * breaks an older phone.
 *
 * Reference: Thrift compact protocol spec; parquet-format `parquet.thrift`.
 */
internal class ThriftCompactReader(private val buf: ByteBuffer) {

    /** Compact protocol wire types. */
    object Type {
        const val STOP = 0
        const val BOOLEAN_TRUE = 1
        const val BOOLEAN_FALSE = 2
        const val BYTE = 3
        const val I16 = 4
        const val I32 = 5
        const val I64 = 6
        const val DOUBLE = 7
        const val BINARY = 8
        const val LIST = 9
        const val SET = 10
        const val MAP = 11
        const val STRUCT = 12
    }

    class FieldHeader(val type: Int, val id: Int) {
        val isStop: Boolean get() = type == Type.STOP
    }

    private class Frame(var lastFieldId: Int = 0)

    private val frames = ArrayDeque<Frame>().apply { addLast(Frame()) }

    init {
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    /** Position of the next unread byte. */
    val position: Int get() = buf.position()

    fun readFieldHeader(): FieldHeader {
        val byte = buf.get().toInt() and 0xFF
        val type = byte and 0x0F
        if (type == Type.STOP) return FieldHeader(Type.STOP, 0)
        val delta = byte ushr 4
        val frame = frames.last()
        val id = if (delta == 0) readZigZag32() else frame.lastFieldId + delta
        frame.lastFieldId = id
        return FieldHeader(type, id)
    }

    /** Enter a nested struct: field ids inside restart from 0. Pair with [exitStruct]. */
    fun enterStruct() {
        frames.addLast(Frame())
    }

    fun exitStruct() {
        frames.removeLast()
    }

    /** Reads a whole struct, calling [onField] for every field (the callback must consume the value). */
    inline fun readStruct(onField: (FieldHeader) -> Unit) {
        enterStruct()
        while (true) {
            val header = readFieldHeader()
            if (header.isStop) break
            onField(header)
        }
        exitStruct()
    }

    class ListHeader(val elementType: Int, val size: Int)

    fun readListHeader(): ListHeader {
        val byte = buf.get().toInt() and 0xFF
        val elementType = byte and 0x0F
        val sizeNibble = byte ushr 4
        val size = if (sizeNibble == 0x0F) readVarint32() else sizeNibble
        require(size >= 0) { "negative list size" }
        return ListHeader(elementType, size)
    }

    fun readVarint32(): Int {
        val v = readVarint64()
        require(v in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL) { "varint out of i32 range: $v" }
        return v.toInt()
    }

    fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    fun readZigZag32(): Int {
        val n = readVarint32()
        return (n ushr 1) xor -(n and 1)
    }

    fun readZigZag64(): Long {
        val n = readVarint64()
        return (n ushr 1) xor -(n and 1L)
    }

    /** i16, i32 and i64 all travel as zig-zag varints. */
    fun readI16(): Int = readZigZag32()
    fun readI32(): Int = readZigZag32()
    fun readI64(): Long = readZigZag64()

    fun readDouble(): Double = buf.double

    fun readBinary(): ByteArray {
        val length = readVarint32()
        require(length >= 0 && length <= buf.remaining()) { "binary length $length exceeds buffer" }
        val out = ByteArray(length)
        buf.get(out)
        return out
    }

    fun readString(): String = String(readBinary(), Charsets.UTF_8)

    /** A boolean field carries its value in the header type; a boolean list element is one byte. */
    fun booleanFromHeader(header: FieldHeader): Boolean = when (header.type) {
        Type.BOOLEAN_TRUE -> true
        Type.BOOLEAN_FALSE -> false
        else -> throw IllegalArgumentException("field ${header.id} is not a boolean")
    }

    /** Skips one value of [type] (the header has already been read). */
    fun skip(type: Int) {
        when (type) {
            Type.BOOLEAN_TRUE, Type.BOOLEAN_FALSE -> Unit // value lives in the header
            Type.BYTE -> buf.get()
            Type.I16, Type.I32, Type.I64 -> readVarint64()
            Type.DOUBLE -> buf.position(buf.position() + 8)
            Type.BINARY -> {
                val length = readVarint32()
                buf.position(buf.position() + length)
            }
            Type.LIST, Type.SET -> {
                val list = readListHeader()
                repeat(list.size) { skipListElement(list.elementType) }
            }
            Type.MAP -> {
                val size = readVarint32()
                if (size > 0) {
                    val kv = buf.get().toInt() and 0xFF
                    val keyType = kv ushr 4
                    val valueType = kv and 0x0F
                    repeat(size) {
                        skipListElement(keyType)
                        skipListElement(valueType)
                    }
                }
            }
            Type.STRUCT -> readStruct { header -> skip(header.type) }
            else -> throw IllegalArgumentException("unknown thrift type $type")
        }
    }

    /** Inside a list a boolean is a full byte, unlike a struct field. */
    private fun skipListElement(type: Int) {
        if (type == Type.BOOLEAN_TRUE || type == Type.BOOLEAN_FALSE) buf.get() else skip(type)
    }

    fun readListBoolean(): Boolean = (buf.get().toInt() and 0x0F) == Type.BOOLEAN_TRUE
}

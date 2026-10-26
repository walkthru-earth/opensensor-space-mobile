package space.opensensor.data.history.parquet

/**
 * Minimal reader for the Thrift Compact Protocol, which is what Parquet uses for its footer and
 * page headers. Only what a *reader* of our files needs: field headers, varints, zig-zag integers,
 * binaries, lists and structs, plus skipping of anything we do not model so a newer writer never
 * breaks an older phone.
 *
 * Reference: Thrift compact protocol spec; parquet-format `parquet.thrift`.
 */
internal class ThriftCompactReader(private val buf: ByteCursor) {

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

    /** A field's wire type and id, packed into one Int so reading a header allocates nothing. */
    @kotlin.jvm.JvmInline
    value class FieldHeader(private val packed: Int) {
        constructor(type: Int, id: Int) : this((id shl 4) or (type and 0x0F))

        val type: Int get() = packed and 0x0F
        val id: Int get() = packed shr 4
        val isStop: Boolean get() = type == Type.STOP
    }

    /** The last field id of every open struct, innermost at [depth]. */
    private var lastFieldIds = IntArray(4)
    private var depth = 0

    /** Position of the next unread byte, relative to the start of the window. */
    val position: Int get() = buf.pos - buf.start

    fun readFieldHeader(): FieldHeader {
        val byte = buf.getU8()
        val type = byte and 0x0F
        if (type == Type.STOP) return FieldHeader(Type.STOP, 0)
        val delta = byte ushr 4
        val id = if (delta == 0) readZigZag32() else lastFieldIds[depth] + delta
        require(id in 0..Short.MAX_VALUE) { "thrift field id $id out of range" }
        lastFieldIds[depth] = id
        return FieldHeader(type, id)
    }

    /** Enter a nested struct: field ids inside restart from 0. Pair with [exitStruct]. */
    fun enterStruct() {
        require(depth < MAX_DEPTH) { "thrift structs nested deeper than $MAX_DEPTH" }
        if (++depth == lastFieldIds.size) lastFieldIds = lastFieldIds.copyOf(depth * 2)
        lastFieldIds[depth] = 0
    }

    fun exitStruct() {
        check(depth > 0) { "exitStruct without enterStruct" }
        depth--
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

    /** A list's element type and size, packed into one Long. */
    @kotlin.jvm.JvmInline
    value class ListHeader(private val packed: Long) {
        constructor(elementType: Int, size: Int) : this((size.toLong() shl 4) or (elementType and 0x0F).toLong())

        val elementType: Int get() = (packed and 0x0F).toInt()
        val size: Int get() = (packed shr 4).toInt()
    }

    fun readListHeader(): ListHeader {
        val byte = buf.getU8()
        val elementType = byte and 0x0F
        val sizeNibble = byte ushr 4
        val size = if (sizeNibble == 0x0F) readVarint32() else sizeNibble
        require(size >= 0) { "negative list size" }
        // Every element takes at least one byte, so a larger count is a corrupt or truncated footer.
        require(size <= buf.remaining) { "list of $size elements in ${buf.remaining} bytes" }
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
            val b = buf.getU8()
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

    /**
     * Refuses a field whose wire type is not the one its id has in `parquet.thrift`. Without it a
     * corrupt footer could read a string length as an i32, or skip a struct as a varint, and go on
     * parsing garbage as if it were fields.
     */
    fun expect(header: FieldHeader, type: Int) {
        require(header.type == type) { "thrift field ${header.id} has wire type ${header.type}, expected $type" }
    }

    /** The value of [header], checked to be an i16, i32, i64, double, binary or string field. */
    fun readI16(header: FieldHeader): Int { expect(header, Type.I16); return readI16() }
    fun readI32(header: FieldHeader): Int { expect(header, Type.I32); return readI32() }
    fun readI64(header: FieldHeader): Long { expect(header, Type.I64); return readI64() }
    fun readDouble(header: FieldHeader): Double { expect(header, Type.DOUBLE); return readDouble() }
    fun readBinary(header: FieldHeader): ByteArray { expect(header, Type.BINARY); return readBinary() }
    fun readString(header: FieldHeader): String { expect(header, Type.BINARY); return readString() }

    /** The header of the list in [header], checked to be a list of [elementType]. */
    fun readListHeader(header: FieldHeader, elementType: Int): ListHeader {
        expect(header, Type.LIST)
        val list = readListHeader()
        // An empty list may carry any element type, PyArrow writes some as 0.
        require(list.size == 0 || list.elementType == elementType) { "thrift field ${header.id} lists type ${list.elementType}, expected $elementType" }
        return list
    }

    /** Reads the struct in [header], checked to be a struct field. */
    inline fun readStruct(header: FieldHeader, onField: (FieldHeader) -> Unit) {
        expect(header, Type.STRUCT)
        readStruct(onField)
    }

    fun readDouble(): Double = buf.getDouble()

    fun readBinary(): ByteArray {
        val length = readVarint32()
        require(length >= 0 && length <= buf.remaining) { "binary length $length exceeds buffer" }
        val out = ByteArray(length)
        buf.get(out)
        return out
    }

    fun readString(): String {
        val length = readVarint32()
        require(length >= 0 && length <= buf.remaining) { "binary length $length exceeds buffer" }
        val from = buf.pos
        buf.pos += length
        return buf.data.decodeToString(from, from + length)
    }

    /** A boolean field carries its value in the header type; a boolean list element is one byte. */
    fun booleanFromHeader(header: FieldHeader): Boolean = when (header.type) {
        Type.BOOLEAN_TRUE -> true
        Type.BOOLEAN_FALSE -> false
        else -> throw IllegalArgumentException("field ${header.id} is not a boolean")
    }

    /** Skips one value of [type] (the header has already been read). */
    fun skip(type: Int) {
        require(skipDepth < MAX_DEPTH) { "thrift values nested deeper than $MAX_DEPTH" }
        skipDepth++
        try {
            skipValue(type)
        } finally {
            skipDepth--
        }
    }

    private var skipDepth = 0

    private fun skipValue(type: Int) {
        when (type) {
            Type.BOOLEAN_TRUE, Type.BOOLEAN_FALSE -> Unit // value lives in the header
            Type.BYTE -> buf.get()
            Type.I16, Type.I32, Type.I64 -> readVarint64()
            Type.DOUBLE -> buf.skip(8)
            Type.BINARY -> {
                val length = readVarint32()
                buf.skip(length)
            }
            Type.LIST, Type.SET -> {
                val list = readListHeader()
                repeat(list.size) { skipListElement(list.elementType) }
            }
            Type.MAP -> {
                val size = readVarint32()
                // Every entry takes at least two bytes, so a larger count is a corrupt footer.
                require(size >= 0) { "negative map size $size" }
                require(size <= buf.remaining) { "map of $size entries in ${buf.remaining} bytes" }
                if (size > 0) {
                    val kv = buf.getU8()
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

    fun readListBoolean(): Boolean = (buf.getU8() and 0x0F) == Type.BOOLEAN_TRUE

    private companion object {
        /** Parquet nests a few levels deep. A hostile file must fail, not overflow the stack. */
        const val MAX_DEPTH = 64
    }
}

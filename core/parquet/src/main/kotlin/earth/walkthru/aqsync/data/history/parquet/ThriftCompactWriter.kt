package earth.walkthru.aqsync.data.history.parquet

import java.io.ByteArrayOutputStream

/** Thrift Compact Protocol writer — the mirror of [ThriftCompactReader], for footers and page headers. */
internal class ThriftCompactWriter {
    private val out = ByteArrayOutputStream()
    private val lastFieldIds = ArrayDeque<Int>().apply { addLast(0) }

    fun toByteArray(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()

    private fun fieldHeader(id: Int, type: Int) {
        val last = lastFieldIds.removeLast()
        val delta = id - last
        if (delta in 1..15) out.write((delta shl 4) or type) else {
            out.write(type)
            writeZigZag32(id)
        }
        lastFieldIds.addLast(id)
    }

    fun structBegin() { lastFieldIds.addLast(0) }
    fun structEnd() { out.write(ThriftCompactReader.Type.STOP); lastFieldIds.removeLast() }

    /** A field whose value is a struct: header, then [body], then STOP. */
    inline fun struct(id: Int, body: () -> Unit) {
        fieldBegin(id, ThriftCompactReader.Type.STRUCT)
        structBegin()
        body()
        structEnd()
    }

    fun fieldBegin(id: Int, type: Int) = fieldHeader(id, type)

    fun bool(id: Int, value: Boolean) = fieldHeader(id, if (value) ThriftCompactReader.Type.BOOLEAN_TRUE else ThriftCompactReader.Type.BOOLEAN_FALSE)
    fun i16(id: Int, value: Int) { fieldHeader(id, ThriftCompactReader.Type.I16); writeZigZag32(value) }
    fun i32(id: Int, value: Int) { fieldHeader(id, ThriftCompactReader.Type.I32); writeZigZag32(value) }
    fun i64(id: Int, value: Long) { fieldHeader(id, ThriftCompactReader.Type.I64); writeZigZag64(value) }
    fun binary(id: Int, value: ByteArray) { fieldHeader(id, ThriftCompactReader.Type.BINARY); writeBinary(value) }
    fun string(id: Int, value: String) = binary(id, value.toByteArray(Charsets.UTF_8))

    /** A list field of [size] elements of [elementType]; the caller then writes each element. */
    fun listBegin(id: Int, elementType: Int, size: Int) {
        fieldHeader(id, ThriftCompactReader.Type.LIST)
        listHeader(elementType, size)
    }

    private fun listHeader(elementType: Int, size: Int) {
        if (size < 15) out.write((size shl 4) or elementType) else {
            out.write(0xF0 or elementType)
            writeVarint(size.toLong())
        }
    }

    /** Elements inside a list (no field header). */
    fun listI32(value: Int) = writeZigZag32(value)
    fun listString(value: String) = writeBinary(value.toByteArray(Charsets.UTF_8))
    inline fun listStruct(body: () -> Unit) { structBegin(); body(); structEnd() }

    private fun writeBinary(bytes: ByteArray) { writeVarint(bytes.size.toLong()); out.write(bytes) }
    private fun writeZigZag32(v: Int) = writeVarint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFF_FFFFL)
    private fun writeZigZag64(v: Long) = writeVarint((v shl 1) xor (v shr 63))

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

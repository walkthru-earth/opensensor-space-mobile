package earth.walkthru.aqsync.data.history

// Test-only. Shared as a source directory by the :core:history tests (JVM and iOS) and the :app unit
// tests, which both read the :core:parquet device fixtures. Never part of a release build.

/**
 * [bytes] with the old footer keys the app used to read (`station_id`, `boot_id`, `schema_version`,
 * `purpose`) moved into an `opensensor` entry, and the dropped `reconciliation_anchor_*` keys removed.
 * Only the key-value list of the Thrift FileMetaData changes. The data pages, the row groups and every
 * other footer field stay byte for byte, so a migrated fixture decodes to the same rows. A file that
 * already has an `opensensor` entry, or none of the old keys, comes back unchanged.
 */
fun migrateLegacyFooter(bytes: ByteArray): ByteArray {
    if (bytes.size < 12 || !bytes.endsWithMagic()) return bytes
    val footerLength = bytes.leInt(bytes.size - 8)
    val footerStart = bytes.size - 8 - footerLength
    val footer = bytes.copyOfRange(footerStart, bytes.size - 8)
    val list = Compact(footer, 0).keyValueSpan() ?: return bytes
    val keyValues = list.entries
    val old = keyValues.toMap()
    if (OpenSensorFooter.KEY in old || (LEGACY.none { it in old } && old.keys.none { it.startsWith(ANCHOR) })) return bytes
    val moved = OpenSensorFooter(
        stationId = old["station_id"]?.takeIf { it.isNotEmpty() },
        bootId = old["boot_id"]?.takeIf { it.isNotEmpty() },
        schema = old["schema_version"]?.takeIf { it.isNotEmpty() },
        purpose = old["purpose"]?.takeIf { it.isNotEmpty() },
    )
    val kept = keyValues.filter { (k, _) -> k !in LEGACY && !k.startsWith(ANCHOR) } + (OpenSensorFooter.KEY to moved.encode())
    val newFooter = footer.copyOfRange(0, list.start) + encodeKeyValues(kept) + footer.copyOfRange(list.end, footer.size)
    return bytes.copyOfRange(0, footerStart) + newFooter + newFooter.size.leBytes() + MAGIC
}

private val LEGACY = setOf("station_id", "boot_id", "schema_version", "purpose")
private const val ANCHOR = "reconciliation_anchor_"
private val MAGIC = "PAR1".encodeToByteArray()

private fun ByteArray.endsWithMagic() = (0 until 4).all { this[size - 4 + it] == MAGIC[it] }

private fun ByteArray.leInt(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

private fun Int.leBytes() = byteArrayOf(toByte(), (this ushr 8).toByte(), (this ushr 16).toByte(), (this ushr 24).toByte())

private class KeyValueSpan(val start: Int, val end: Int, val entries: List<Pair<String, String>>)

/** A Thrift compact protocol reader over [b], just enough to find FileMetaData field 5. */
private class Compact(val b: ByteArray, var p: Int) {
    fun byte(): Int = b[p++].toInt() and 0xFF

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val x = byte()
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fieldId(header: Int, last: Int): Int {
        val delta = header ushr 4
        if (delta != 0) return last + delta
        val z = varint()
        return ((z ushr 1) xor -(z and 1)).toInt()
    }

    fun binary(): String {
        val n = varint().toInt()
        return b.decodeToString(p, p + n).also { p += n }
    }

    fun listHeader(): Pair<Int, Int> {
        val h = byte()
        var n = h ushr 4
        if (n == 15) n = varint().toInt()
        return n to (h and 0x0F)
    }

    fun skip(type: Int) {
        when (type) {
            BOOL_TRUE, BOOL_FALSE -> Unit
            BYTE -> p++
            I16, I32, I64 -> varint()
            DOUBLE -> p += 8
            BINARY -> {
                val n = varint().toInt()
                p += n
            }
            LIST, SET -> {
                val (n, t) = listHeader()
                repeat(n) { if (t == BOOL_TRUE || t == BOOL_FALSE) p++ else skip(t) }
            }
            MAP -> {
                val n = varint().toInt()
                if (n > 0) {
                    val kv = byte()
                    repeat(n) { skip(kv ushr 4); skip(kv and 0x0F) }
                }
            }
            STRUCT -> {
                var last = 0
                while (true) {
                    val h = byte()
                    if (h == 0) return
                    last = fieldId(h, last)
                    skip(h and 0x0F)
                }
            }
            else -> error("unknown thrift type $type")
        }
    }

    /** The span and entries of FileMetaData field 5, the key-value list, or null when the file has none. */
    fun keyValueSpan(): KeyValueSpan? {
        var last = 0
        while (true) {
            val h = byte()
            if (h == 0) return null
            last = fieldId(h, last)
            val type = h and 0x0F
            if (last != 5 || type != LIST) { skip(type); continue }
            val start = p
            val (n, _) = listHeader()
            val entries = ArrayList<Pair<String, String>>(n)
            repeat(n) {
                var key = ""
                var value = ""
                var f = 0
                while (true) {
                    val fh = byte()
                    if (fh == 0) break
                    f = fieldId(fh, f)
                    when (f) {
                        1 -> key = binary()
                        2 -> value = binary()
                        else -> skip(fh and 0x0F)
                    }
                }
                entries += key to value
            }
            return KeyValueSpan(start, p, entries)
        }
    }

    companion object {
        const val BOOL_TRUE = 1
        const val BOOL_FALSE = 2
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
}

/** [entries] as a Thrift compact `list<KeyValue>`, the value of FileMetaData field 5. */
private fun encodeKeyValues(entries: List<Pair<String, String>>): ByteArray {
    val out = ArrayList<Byte>()
    fun varint(v: Int) {
        var x = v
        while (x and 0x7F.inv() != 0) { out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
        out += x.toByte()
    }
    fun binaryField(s: String) {
        out += ((1 shl 4) or Compact.BINARY).toByte()
        val bytes = s.encodeToByteArray()
        varint(bytes.size)
        bytes.forEach { out += it }
    }
    if (entries.size < 15) out += ((entries.size shl 4) or Compact.STRUCT).toByte() else { out += (0xF0 or Compact.STRUCT).toByte(); varint(entries.size) }
    for ((k, v) in entries) {
        binaryField(k)
        binaryField(v)
        out += 0
    }
    return out.toByteArray()
}

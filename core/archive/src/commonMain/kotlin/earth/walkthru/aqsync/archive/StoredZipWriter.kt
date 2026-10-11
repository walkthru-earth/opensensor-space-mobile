package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteReader
import earth.walkthru.aqsync.data.protocol.Crc32
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Where an export writes its bytes, in order. A platform file, or memory in tests. */
interface ExportSink : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
}

/** Thrown when an entry's bytes changed between the checksum pass and the copy. */
class ExportChangedException(path: String) : IllegalStateException("$path changed while it was exported")

/**
 * Writes a ZIP archive of STORED (uncompressed) entries to [sink], in plain common Kotlin.
 *
 * Parquet pages are compressed on the device already, so storing is what the old Android export did
 * (`ZipOutputStream` at level 0). Each entry is read twice through its [ByteReader]: once for the
 * CRC-32 the local header needs, once to copy. A second CRC over the copied bytes must match, so a
 * file that changes underneath fails the export instead of shipping a corrupt entry. No data
 * descriptor is used, because Java's `ZipInputStream` cannot read STORED entries that use one.
 *
 * Zip64 records are added only when needed, for an entry or an offset of 4 GiB or more, or more than
 * 65 535 entries ([zip64Limit] and [entryLimit] are lowered by tests). Names are UTF-8 (flag bit 11).
 * Times are DOS times in UTC, clamped to 1980. The writer never reads or changes anything but the
 * bytes it is handed.
 */
class StoredZipWriter(
    private val sink: ExportSink,
    private val zip64Limit: Long = 0xFFFFFFFFL,
    private val entryLimit: Int = 0xFFFF,
    /** Runs before each slice is read, so a caller can stop a long write (see [ArchiveExport.writeZip]). */
    private val checkpoint: () -> Unit = {},
) {
    private class Central(val name: ByteArray, val crc: Long, val size: Long, val offset: Long, val time: Int, val date: Int)

    private val entries = ArrayList<Central>()
    private val names = HashSet<String>()
    private var written = 0L
    private var finished = false

    /** Adds one STORED entry named [path] with the bytes of [reader]. */
    fun add(path: String, reader: ByteReader, modifiedEpochMs: Long) {
        check(!finished) { "zip already finished" }
        require(isSafeEntryName(path)) { "unsafe zip entry name" }
        require(names.add(path)) { "duplicate zip entry $path" }
        val size = reader.size
        val crc = checksum(reader)
        val name = path.encodeToByteArray()
        val (time, date) = dosTime(modifiedEpochMs)
        val offset = written
        val big = size >= zip64Limit
        writeLocalHeader(name, crc, size, time, date, big)
        val copied = copy(reader)
        if (copied != crc) throw ExportChangedException(path)
        entries += Central(name, crc, size, offset, time, date)
    }

    /** Writes the central directory and the end records. The sink is left open for the caller to close. */
    fun finish() {
        check(!finished) { "zip already finished" }
        finished = true
        val cdStart = written
        for (e in entries) writeCentral(e)
        val cdSize = written - cdStart
        val needs64 = entries.size > entryLimit || cdStart >= zip64Limit || cdSize >= zip64Limit
        if (needs64) {
            val eocd64 = written
            le32(0x06064b50)
            le64(44)
            le16(VERSION_ZIP64)
            le16(VERSION_ZIP64)
            le32(0)
            le32(0)
            le64(entries.size.toLong())
            le64(entries.size.toLong())
            le64(cdSize)
            le64(cdStart)
            le32(0x07064b50)
            le32(0)
            le64(eocd64)
            le32(1)
        }
        le32(0x06054b50)
        le16(0)
        le16(0)
        val count = if (needs64) 0xFFFF else entries.size
        le16(count)
        le16(count)
        le32(if (needs64) 0xFFFFFFFFL else cdSize)
        le32(if (needs64) 0xFFFFFFFFL else cdStart)
        le16(0)
    }

    private fun writeLocalHeader(name: ByteArray, crc: Long, size: Long, time: Int, date: Int, big: Boolean) {
        le32(0x04034b50)
        le16(if (big) VERSION_ZIP64 else VERSION_STORED)
        le16(FLAG_UTF8)
        le16(0)
        le16(time)
        le16(date)
        le32(crc)
        le32(if (big) 0xFFFFFFFFL else size)
        le32(if (big) 0xFFFFFFFFL else size)
        le16(name.size)
        le16(if (big) 20 else 0)
        bytes(name)
        if (big) {
            le16(0x0001)
            le16(16)
            le64(size)
            le64(size)
        }
    }

    private fun writeCentral(e: Central) {
        val bigSize = e.size >= zip64Limit
        val bigOffset = e.offset >= zip64Limit
        val extra = (if (bigSize) 16 else 0) + (if (bigOffset) 8 else 0)
        val big = extra > 0
        le32(0x02014b50)
        le16(if (big) VERSION_ZIP64 else VERSION_STORED)
        le16(if (big) VERSION_ZIP64 else VERSION_STORED)
        le16(FLAG_UTF8)
        le16(0)
        le16(e.time)
        le16(e.date)
        le32(e.crc)
        le32(if (bigSize) 0xFFFFFFFFL else e.size)
        le32(if (bigSize) 0xFFFFFFFFL else e.size)
        le16(e.name.size)
        le16(if (big) extra + 4 else 0)
        le16(0)
        le16(0)
        le16(0)
        le32(0)
        le32(if (bigOffset) 0xFFFFFFFFL else e.offset)
        bytes(e.name)
        if (big) {
            le16(0x0001)
            le16(extra)
            if (bigSize) {
                le64(e.size)
                le64(e.size)
            }
            if (bigOffset) le64(e.offset)
        }
    }

    private fun checksum(reader: ByteReader): Long {
        val crc = Crc32()
        forEachSlice(reader) { crc.update(it) }
        return crc.value
    }

    private fun copy(reader: ByteReader): Long {
        val crc = Crc32()
        forEachSlice(reader) {
            crc.update(it)
            bytes(it)
        }
        return crc.value
    }

    private inline fun forEachSlice(reader: ByteReader, block: (ByteArray) -> Unit) {
        val size = reader.size
        var at = 0L
        while (at < size) {
            checkpoint()
            val n = minOf(SLICE.toLong(), size - at).toInt()
            block(reader.read(at, n))
            at += n
        }
    }

    private val scratch = ByteArray(8)

    private fun le16(v: Int) = le(v.toLong(), 2)
    private fun le32(v: Int) = le(v.toLong() and 0xFFFFFFFFL, 4)
    private fun le32(v: Long) = le(v, 4)
    private fun le64(v: Long) = le(v, 8)

    private fun le(v: Long, n: Int) {
        for (i in 0 until n) scratch[i] = (v ushr (8 * i)).toByte()
        sink.write(scratch, 0, n)
        written += n
    }

    private fun bytes(b: ByteArray) {
        sink.write(b, 0, b.size)
        written += b.size
    }

    companion object {
        private const val SLICE = 64 * 1024
        private const val VERSION_STORED = 10
        private const val VERSION_ZIP64 = 45
        private const val FLAG_UTF8 = 1 shl 11

        /** DOS time and date fields of [epochMs] in UTC. Before 1980 is 1980-01-01 00:00, past 2107 the last DOS day. */
        fun dosTime(epochMs: Long): Pair<Int, Int> {
            val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
            if (t.year < 1980) return 0 to ((0 shl 9) or (1 shl 5) or 1)
            if (t.year > 2107) return ((23 shl 11) or (59 shl 5) or 29) to ((127 shl 9) or (12 shl 5) or 31)
            val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
            val date = ((t.year - 1980) shl 9) or ((t.month.ordinal + 1) shl 5) or t.day
            return time to date
        }
    }
}

/**
 * A relative, forward slash entry name that cannot leave the folder it is unzipped into on any
 * system. No empty, `.` or `..` segment, no backslash, no control character and no drive letter.
 */
internal fun isSafeEntryName(path: String): Boolean =
    path.isNotEmpty() &&
        !path.startsWith("/") &&
        path.none { it == '\\' || it.code < 0x20 || it.code == 0x7F } &&
        !(path.length >= 2 && path[1] == ':') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

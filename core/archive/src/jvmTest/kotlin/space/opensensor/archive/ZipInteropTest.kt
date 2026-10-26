package space.opensensor.archive

import space.opensensor.data.history.parquet.ByteArrayReader
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The common writer's zips open in Java's two ZIP readers, and the JVM export space cleans up. */
class ZipInteropTest {
    private val root: File = Files.createTempDirectory("export-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val a = ByteArray(150_000) { (it % 251).toByte() }
    private val b = "PAR1 خفيف".encodeToByteArray()

    private fun write(zip64Limit: Long = 0xFFFFFFFFL, entryLimit: Int = 0xFFFF): File {
        val file = File(root, "t.zip")
        val out = file.outputStream().buffered()
        val sink = object : ExportSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = out.close()
        }
        sink.use {
            StoredZipWriter(it, zip64Limit, entryLimit).apply {
                add("ST1/station=ST1/year=2026/a.parquet", ByteArrayReader(a), 1_709_647_629_000)
                add("ST2/ب.parquet", ByteArrayReader(b), 1_709_647_629_000)
                finish()
            }
        }
        return file
    }

    private fun checkBoth(file: File) {
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("ST1/station=ST1/year=2026/a.parquet", "ST2/ب.parquet"), names)
            assertContentEquals(a, zip.getInputStream(zip.getEntry(names[0])).readBytes())
            assertContentEquals(b, zip.getInputStream(zip.getEntry(names[1])).readBytes())
        }
        ZipInputStream(file.inputStream()).use { zin ->
            val seen = ArrayList<ByteArray>()
            while (zin.nextEntry != null) seen += zin.readBytes()
            assertContentEquals(a, seen[0])
            assertContentEquals(b, seen[1])
        }
    }

    @Test
    fun javaReadsAPlainZip() = checkBoth(write())

    @Test
    fun javaReadsAZip64Zip() = checkBoth(write(zip64Limit = 1000))

    @Test
    fun javaReadsZip64EndRecords() {
        ZipFile(write(entryLimit = 1)).use { assertEquals(2, it.size()) }
    }

    @Test
    fun directoryEntriesListFilesUnderAPrefix() {
        val derived = File(root, "derived/v1").apply { mkdirs() }
        File(derived, "A").mkdirs()
        File(derived, "A/d.parquet").writeBytes(a)
        File(derived, "A/d.manifest.json").writeText("{}")
        val entries = ArchiveExport.dailyEntries(directoryEntries(derived, "derived/v1"), setOf("A"))!!
        assertEquals(listOf("derived/v1/A/d.manifest.json", "derived/v1/A/d.parquet"), entries.map { it.path })
    }

    @Test
    fun writeZipStreamsIntoAForwardOnlyStream() {
        // A Storage Access Framework document is a plain OutputStream with no seek, as here.
        val out = java.io.ByteArrayOutputStream()
        val sink = object : ExportSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = Unit
        }
        val entries = listOf(ExportEntry("A/x.parquet", 0) { ByteArrayReader(a) }, ExportEntry("B/y.parquet", 0) { ByteArrayReader(b) })
        ArchiveExport.writeZip(sink, entries)
        assertTrue(out.size() <= ArchiveExport.plannedZipBytes(entries))
        ZipInputStream(out.toByteArray().inputStream()).use { zin ->
            val seen = LinkedHashMap<String, ByteArray>()
            while (true) {
                val e = zin.nextEntry ?: break
                seen[e.name] = zin.readBytes()
            }
            assertEquals(listOf("A/x.parquet", "B/y.parquet"), seen.keys.toList())
            assertContentEquals(a, seen["A/x.parquet"])
            assertContentEquals(b, seen["B/y.parquet"])
        }
    }
}

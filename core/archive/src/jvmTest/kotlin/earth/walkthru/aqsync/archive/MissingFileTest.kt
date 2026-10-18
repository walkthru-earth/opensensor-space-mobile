package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** On the JVM and Android, a zip leaves out a final that is gone and fails on any other open error (#168). */
class MissingFileTest {
    private val root: File = Files.createTempDirectory("missing-file-test").toFile()

    @AfterTest
    fun cleanup() {
        root.walkTopDown().forEach { it.setReadable(true) }
        root.deleteRecursively()
    }

    private fun file(name: String): File = File(root, name).apply { writeBytes("PAR1 $name".encodeToByteArray()) }

    private fun entry(f: File) = ExportEntry("A/${f.name}", 0) { LocalFileReader(f) }

    @Test
    fun aFinalDeletedAfterTheListingIsLeftOut() {
        val kept = file("a.parquet")
        val gone = file("b.parquet")
        val entries = listOf(entry(kept), entry(gone))
        assertTrue(gone.delete())
        assertEquals(1, ArchiveExport.writeZip(MemorySink(), entries))
        assertEquals(ArchiveExport.plannedZipBytes(listOf(entry(kept))), ArchiveExport.plannedZipBytes(entries))
    }

    @Test
    fun aFinalThatCannotBeReadFailsTheExport() {
        val kept = file("a.parquet")
        val denied = file("b.parquet")
        assertTrue(denied.setReadable(false))
        // A user that reads everything, such as root, cannot see EACCES here.
        if (denied.canRead()) return
        val entries = listOf(entry(kept), entry(denied))
        assertFailsWith<AccessDeniedException> { ArchiveExport.writeZip(MemorySink(), entries) }
        assertFailsWith<AccessDeniedException> { ArchiveExport.plannedZipBytes(entries) }
    }

    @Test
    fun onlyAFileThatIsNotThereCountsAsGone() {
        assertTrue(isMissingFile(ArchiveFileMissingException("x")))
        assertTrue(isMissingFile(NoSuchFileException("x")))
        assertTrue(isMissingFile(FileNotFoundException("x is not a regular file")))
        assertTrue(isMissingFile(FileNotFoundException("x (No such file or directory)")))
        assertFalse(isMissingFile(FileNotFoundException("x (Permission denied)")))
        assertFalse(isMissingFile(FileNotFoundException("x (Too many open files)")))
        assertFalse(isMissingFile(AccessDeniedException("x")))
        assertFalse(isMissingFile(FileSystemException("x", null, "Input/output error")))
        assertFalse(isMissingFile(IllegalStateException("x")))
    }
}

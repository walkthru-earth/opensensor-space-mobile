package space.opensensor.archive

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
        root.walkTopDown().forEach { it.setReadable(true); it.setExecutable(true) }
        root.deleteRecursively()
    }

    private fun file(name: String): File = File(root, name).apply { writeBytes("PAR1 $name".encodeToByteArray()) }

    private fun entry(f: File) = ExportEntry("A/${f.name}", 0) { openArchiveFile(f) }

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
    fun aFinalUnderAFolderThatCannotBeSearchedFailsTheExport() {
        val kept = file("a.parquet")
        val dir = File(root, "locked").apply { mkdirs() }
        val hidden = File(dir, "b.parquet").apply { writeBytes("PAR1 b".encodeToByteArray()) }
        assertTrue(dir.setExecutable(false) && dir.setReadable(false))
        // A user that reads everything, such as root, still sees the file here.
        if (hidden.isFile) return
        val entries = listOf(entry(kept), entry(hidden))
        val failure = assertFailsWith<FileNotFoundException> { ArchiveExport.writeZip(MemorySink(), entries) }
        assertFalse(isMissingFile(failure))
        assertFailsWith<FileNotFoundException> { ArchiveExport.plannedZipBytes(entries) }
    }

    @Test
    fun onlyAFileThatIsNotThereCountsAsGone() {
        assertTrue(isMissingFile(ArchiveFileMissingException("x")))
        assertTrue(isMissingFile(NoSuchFileException("x")))
        assertFailsWith<ArchiveFileMissingException> { openArchiveFile(File(root, "none.parquet")) }
        assertFailsWith<ArchiveFileMissingException> { openArchiveFile(File(root, "no-folder/none.parquet")) }
        assertFalse(isMissingFile(FileNotFoundException("x is not a regular file")))
        assertFalse(isMissingFile(FileNotFoundException("x (No such file or directory)")))
        assertFalse(isMissingFile(FileNotFoundException("x (Permission denied)")))
        assertFalse(isMissingFile(FileNotFoundException("x (Too many open files)")))
        assertFalse(isMissingFile(AccessDeniedException("x")))
        assertFalse(isMissingFile(FileSystemException("x", null, "Input/output error")))
        assertFalse(isMissingFile(IllegalStateException("x")))
    }
}

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.ByteArrayReader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Export runs coexist, leased runs survive the age sweep, and released runs age out (#161). */
class FileExportSpaceTest {
    private val root: File = Files.createTempDirectory("export-space-test").toFile()
    private val exports = File(root, "export")
    private val hour = ExportRuns.MAX_AGE_MS
    private var now = 1_800_000_000_000L
    private val staged = mutableListOf<ExportRun>()

    @AfterTest
    fun cleanup() {
        // Ends every lease this test took, the registry is shared by the process.
        staged.forEach { it.delete() }
        root.deleteRecursively()
    }

    private val a = "PAR1 a".encodeToByteArray()
    private val b = "PAR1 b".encodeToByteArray()

    private fun space() = FileExportSpace(exports) { now }

    private fun zip(space: ExportSpace, bytes: ByteArray, name: String): StagedFile =
        ArchiveExport.stageZip(space, listOf(ExportEntry("A/x.parquet", 0) { ByteArrayReader(bytes) }), name).also { staged += it.run }

    private fun runDir(file: StagedFile) = File(file.path).parentFile

    @Test
    fun aSecondRunKeepsTheFirstRunsStagedFile() {
        val space = space()
        val first = zip(space, a, "one.zip")
        // Android's chooser returned at once, so the first share is released, not finished.
        first.run.release()
        val second = ArchiveExport.stageCopies(space, listOf(ExportEntry("A/x.parquet", 0) { ByteArrayReader(b) })).also { staged += it.single().run }
        assertTrue(File(first.path).isFile, "a new export removed a file an earlier share still offers")
        assertContentEquals(b, File(second.single().path).readBytes())
        second.single().run.delete()
        assertFalse(runDir(second.single()).exists())
        assertTrue(File(first.path).isFile)
    }

    @Test
    fun twoControllersStagingAtOnceKeepBoth() {
        // Two station controllers, each with its own space over the same root.
        val one = zip(space(), a, "one.zip")
        val two = zip(space(), b, "two.zip")
        assertTrue(File(one.path).isFile)
        assertTrue(File(two.path).isFile)
        assertEquals(2, exports.listFiles()!!.size)
    }

    @Test
    fun aRunStillStagingIsNeverSweptByAge() {
        val first = zip(space(), a, "one.zip")
        // Staging outlived the age limit, the run is still leased.
        runDir(first).setLastModified(now - 2 * hour)
        now += 2 * hour
        zip(space(), b, "two.zip")
        assertTrue(File(first.path).isFile, "a leased run was swept")
    }

    @Test
    fun aReleasedRunPastTheAgeLimitIsSweptByTheNextRun() {
        val first = zip(space(), a, "one.zip")
        first.run.release()
        now += hour - 1
        zip(space(), b, "two.zip")
        assertTrue(File(first.path).isFile, "swept before the age limit")
        now += 1
        zip(space(), b, "three.zip")
        assertFalse(runDir(first).exists())
    }

    @Test
    fun releaseCountsTheAgeFromTheHandOver() {
        val first = zip(space(), a, "one.zip")
        // The directory was written long ago, the chooser opened now.
        runDir(first).setLastModified(now - 2 * hour)
        first.run.release()
        zip(space(), b, "two.zip")
        assertTrue(File(first.path).isFile)
    }

    @Test
    fun theSweepTouchesOnlyRunDirectories() {
        exports.mkdirs()
        val stray = File(exports, "notes.txt").apply { writeText("x") }
        val strayDir = File(exports, "keep").apply { mkdirs() }
        stray.setLastModified(now - 2 * hour)
        strayDir.setLastModified(now - 2 * hour)
        zip(space(), a, "one.zip")
        assertTrue(stray.isFile)
        assertTrue(strayDir.isDirectory)
    }

    @Test
    fun theSweepNeverFollowsARunLink() {
        exports.mkdirs()
        val outside = File(root, "outside").apply { mkdirs() }
        val kept = File(outside, "keep.parquet").apply { writeText("x") }
        outside.setLastModified(now - 2 * hour)
        Files.createSymbolicLink(File(exports, "run-link").toPath(), outside.toPath())
        zip(space(), a, "one.zip")
        assertTrue(kept.isFile, "the sweep emptied a folder through a link")
    }
}

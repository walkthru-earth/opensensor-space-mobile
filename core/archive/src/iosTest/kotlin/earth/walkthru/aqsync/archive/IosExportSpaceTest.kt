@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.archive.IosFiles.Kind
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Export runs coexist, leased runs survive the age sweep, and released runs age out from the hand over (#161). */
class IosExportSpaceTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/export-space-test-" + NSUUID().UUIDString()
    private val exports = "$base/export"
    private val hour = ExportRuns.MAX_AGE_MS
    private var now = 1_800_000_000_000L
    private val runs = mutableListOf<ExportRun>()

    @AfterTest
    fun cleanUp() {
        // Ends every lease this test took, the registry is shared by the process.
        runs.forEach { it.delete() }
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun space() = IosExportSpace(exports) { now }

    /** A run with one staged file, the path of that file. */
    private fun stage(space: ExportSpace, name: String): String {
        val run = space.newRun().also { runs += it }
        run.create(name).use { it.write(byteArrayOf(1, 2, 3), 0, 3) }
        return run.path(name)
    }

    private fun exists(path: String) = IosFiles.kind(path) == Kind.FILE

    private fun dirOf(path: String) = path.substringBeforeLast('/')

    /** The file system keeps the time as a double of seconds, so allow a millisecond of rounding. */
    private fun assertNear(expected: Long, actual: Long?) =
        assertTrue(actual != null && kotlin.math.abs(actual - expected) <= 1, "time $actual, expected $expected")

    @Test
    fun aSecondRunKeepsTheFirstRunsStagedFile() {
        val space = space()
        val first = stage(space, "one.zip")
        runs.last().release()
        val second = stage(space, "two.zip")
        assertTrue(exists(first), "a new export removed a file an earlier share still offers")
        assertTrue(exists(second))
        assertEquals(2, IosFiles.children(exports).size)
    }

    @Test
    fun aRunStillStagingIsNeverSweptByAge() {
        val first = stage(space(), "one.zip")
        IosFiles.setModified(dirOf(first), now - 2 * hour)
        now += 2 * hour
        stage(space(), "two.zip")
        assertTrue(exists(first), "a leased run was swept")
    }

    @Test
    fun releaseCountsTheAgeFromTheHandOver() {
        val first = stage(space(), "one.zip")
        // The directory was written long ago, the sheet opened now.
        IosFiles.setModified(dirOf(first), now - 2 * hour)
        assertNear(now - 2 * hour, IosFiles.modifiedEpochMs(dirOf(first)))
        runs.last().release()
        assertNear(now, IosFiles.modifiedEpochMs(dirOf(first)))
        stage(space(), "two.zip")
        assertTrue(exists(first), "a released run was judged by its staging time")
        now += hour
        stage(space(), "three.zip")
        assertEquals(Kind.ABSENT, IosFiles.kind(dirOf(first)))
    }
}

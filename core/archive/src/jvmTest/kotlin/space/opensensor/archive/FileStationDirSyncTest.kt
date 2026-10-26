package space.opensensor.archive

import kotlinx.coroutines.test.runTest
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.JvmAtomicFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** [FileClockAnchorStore] leaves a new station directory to [AtomicFiles], which syncs it. */
class FileStationDirSyncTest {
    private val base = Files.createTempDirectory("station-sync-test-").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun clockAnchorStorePutSyncsANewStationDir() = runTest {
        val root = File(base, "clock-receipts").apply { mkdirs() }
        val recording = RecordingAtomicFiles(JvmAtomicFiles(File(base, AtomicFiles.TEMP_DIR))) { Files.isDirectory(File(it).toPath(), NOFOLLOW_LINKS) }
        val stationDir = File(root, clockTestAnchor.station).path

        FileClockAnchorStore(root, recording).put(clockTestAnchor)

        assertTrue(File(stationDir, ClockAnchorReceipt.relativePath(clockTestAnchor)).isFile)
        assertTrue(stationDir in recording.created, "$stationDir was not left to AtomicFiles, created ${recording.created}")
        assertTrue(stationDir in recording.synced, "$stationDir was not synced, synced ${recording.synced}")
        assertTrue(root.path in recording.synced, "${root.path} was not synced, synced ${recording.synced}")
    }
}

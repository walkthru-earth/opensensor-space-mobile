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

/** [FileClockAnchorStore] leaves a new thing directory to [AtomicFiles], which syncs it. */
class FileThingDirSyncTest {
    private val base = Files.createTempDirectory("thing-sync-test-").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun clockAnchorStorePutSyncsANewThingDir() = runTest {
        val root = File(base, "clock-receipts").apply { mkdirs() }
        val recording = RecordingAtomicFiles(JvmAtomicFiles(File(base, AtomicFiles.TEMP_DIR))) { Files.isDirectory(File(it).toPath(), NOFOLLOW_LINKS) }
        val thingDir = File(root, clockTestAnchor.thing).path

        FileClockAnchorStore(root, recording).put(clockTestAnchor)

        assertTrue(File(thingDir, ClockAnchorReceipt.relativePath(clockTestAnchor)).isFile)
        assertTrue(thingDir in recording.created, "$thingDir was not left to AtomicFiles, created ${recording.created}")
        assertTrue(thingDir in recording.synced, "$thingDir was not synced, synced ${recording.synced}")
        assertTrue(root.path in recording.synced, "${root.path} was not synced, synced ${recording.synced}")
    }
}

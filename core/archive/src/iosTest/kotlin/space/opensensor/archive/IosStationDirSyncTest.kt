@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.IosAtomicFiles
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Each iOS store leaves a new station directory to [AtomicFiles], which syncs it. */
class IosStationDirSyncTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/station-sync-test-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val station = "station-a"
    private val stationDir = "$finals/$station"
    private val key = ArchiveKey(station, "station=$station/year=2026/month=10/day=11/data_0930.parquet")
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val recording = RecordingAtomicFiles(IosAtomicFiles("$base/${AtomicFiles.TEMP_DIR}")) {
        IosFiles.kind(it) == IosFiles.Kind.DIRECTORY
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun assertStationDirSynced(stationDir: String) {
        assertTrue(stationDir in recording.created, "$stationDir was not left to AtomicFiles, created ${recording.created}")
        assertTrue(stationDir in recording.synced, "$stationDir was not synced, synced ${recording.synced}")
        val parent = stationDir.substringBeforeLast('/')
        assertTrue(parent in recording.synced, "$parent was not synced, synced ${recording.synced}")
    }

    @Test
    fun archiveStoreCommitSyncsANewStationDir() = runTest {
        val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Unconfined, recording)
        store.openPartial(key, PartialMeta(bytes.size.toLong(), ParquetVerify.crc32(bytes))).use { it.writeAt(0, bytes) }
        assertTrue(IosFiles.kind(stationDir) == IosFiles.Kind.ABSENT)

        store.commit(key)

        assertTrue(store.exists(key))
        assertStationDirSynced(stationDir)
    }

    @Test
    fun clockAnchorStorePutSyncsANewStationDir() = runTest {
        val root = "$base/clock-receipts"
        val store = IosClockAnchorStore(root, recording)

        store.put(clockTestAnchor)

        assertTrue(IosFiles.kind("$root/${clockTestAnchor.station}/${ClockAnchorReceipt.relativePath(clockTestAnchor)}") == IosFiles.Kind.FILE)
        assertStationDirSynced("$root/${clockTestAnchor.station}")
    }
}

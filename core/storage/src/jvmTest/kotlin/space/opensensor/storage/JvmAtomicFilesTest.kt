package space.opensensor.storage

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmAtomicFilesTest : AtomicFilesContract() {
    private val dir: File = Files.createTempDirectory("atomic-files-").toFile()
    override val root: String = dir.path

    override fun newFiles(tempDir: String): AtomicFiles = JvmAtomicFiles(File(tempDir))
    override fun newTree(): StorageTree = JvmStorageTree()
    override fun newRecordingFiles(tempDir: String, synced: MutableList<String>): AtomicFiles = JvmAtomicFiles(File(tempDir)) { synced += it }

    override fun writeRaw(path: String, bytes: ByteArray) {
        File(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    }

    override fun read(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun budgetReportsTheVolumeAndUsage() = runTest {
        writeRaw("$root/archive/a", ByteArray(10))
        val budget = JvmStorageBudget(StorageRoots(mapOf(StorageRole.Archive to "$root/archive", StorageRole.Derived to "$root/not-yet")))
        assertTrue(budget.freeBytes(StorageClass.Durable) > 0)
        assertTrue(budget.freeBytes(StorageClass.Rebuildable) > 0)
        assertTrue(budget.usage().getValue(StorageClass.Durable) == 10L)
    }
}

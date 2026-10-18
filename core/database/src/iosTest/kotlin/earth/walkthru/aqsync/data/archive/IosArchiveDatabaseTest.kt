@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.PrunedFile
import earth.walkthru.aqsync.data.local.database.AppDatabase
import earth.walkthru.aqsync.data.local.database.deleteTempDir
import earth.walkthru.aqsync.data.local.database.newTempDir
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iOS index on a real database file, with the one time import of an older build's receipts. */
class IosArchiveDatabaseTest {
    private val base = newTempDir()
    private val finals = "$base/SensorArchive"
    private val dir = "$base/database"
    private val store = IosArchiveStore(finals, "$base/archive-partial")
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val name = "station=$station/year=2026/month=09/day=17/data_0930.parquet"
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val fm = NSFileManager.defaultManager

    @AfterTest
    fun cleanUp() = deleteTempDir(base)

    private fun write(path: String, content: ByteArray) {
        fm.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        val data = content.usePinned { NSData.create(bytes = it.addressOf(0), length = content.size.toULong()) }
        check(data.writeToFile(path, true))
    }

    @Test
    fun theFirstOpenImportsTheReceiptsAndALaterOpenReadsTheFileAndDeletesThem() = runTest {
        val receipt = "$finals/$station/$name${ArchiveLayout.LEGACY_RECEIPT_SUFFIX}"
        val prunedFile = "$finals-pruned/pruned.v1"
        write("$finals/$station/$name", bytes)
        write(receipt, """{"size":${bytes.size},"crc":${ParquetVerify.crc32(bytes)},"sourceDevice":"dev"}""".encodeToByteArray())
        write(prunedFile, "pruned-v1\n$station\tother.parquet\t46\t5\t11\n".encodeToByteArray())
        val mark = PrunedFile(station, "other.parquet", 46, 5, 11)

        IosArchiveDatabase(store, dir).apply {
            assertEquals(listOf(name), index.observeAll().first().map { it.name })
            assertEquals(listOf(mark), pruned.observeAll().first())
            close()
        }
        assertTrue(fm.fileExistsAtPath("$dir/${AppDatabase.FILE_NAME}"))
        assertTrue(fm.fileExistsAtPath("$dir/${IosArchiveDatabase.IMPORT_MARKER}"))
        assertTrue(fm.fileExistsAtPath(receipt))
        assertTrue(fm.fileExistsAtPath(prunedFile))

        // A later launch reads the same rows from the file, so the old files go and nothing is replaced.
        write(receipt, """{"size":${bytes.size},"crc":${ParquetVerify.crc32(bytes)},"sourceDevice":"later"}""".encodeToByteArray())
        IosArchiveDatabase(store, dir).apply {
            assertEquals("dev", index.get(ArchiveKey(station, name))?.sourceDevice)
            assertEquals(listOf(mark), pruned.forStation(station))
            close()
        }
        assertFalse(fm.fileExistsAtPath(receipt))
        assertFalse(fm.fileExistsAtPath("$finals-pruned"))
    }

    @Test
    fun downloadAgainAlsoDropsTheOldMarksFile() = runTest {
        write("$finals-pruned/pruned.v1", "pruned-v1\n$station\tother.parquet\t46\t5\t11\n".encodeToByteArray())
        IosArchiveDatabase(store, dir).apply {
            assertEquals(1, pruned.clear())
            close()
        }
        assertFalse(fm.fileExistsAtPath("$finals-pruned"))
        IosArchiveDatabase(store, dir).apply {
            assertEquals(emptyList(), pruned.observeAll().first())
            close()
        }
    }
}

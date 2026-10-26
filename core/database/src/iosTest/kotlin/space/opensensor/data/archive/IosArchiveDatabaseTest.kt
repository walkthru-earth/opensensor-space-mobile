@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.data.archive

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.IosArchiveStore
import space.opensensor.archive.PrunedFile
import space.opensensor.data.local.database.AppDatabase
import space.opensensor.data.local.database.checkpointDurably
import space.opensensor.data.local.database.deleteTempDir
import space.opensensor.data.local.database.newTempDir
import space.opensensor.data.local.database.openAppDatabase
import space.opensensor.data.protocol.ParquetVerify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iOS index on a real database file, with the one time import of an older build's receipts. */
class IosArchiveDatabaseTest {
    private val base = newTempDir()
    private val finals = "$base/SensorArchive"
    private val dir = "$base/database"
    private val store = IosArchiveStore(finals, "$base/archive-partial")
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val name = "station=$thing/year=2026/month=09/day=17/data_0930.parquet"
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
    fun theFirstOpenImportsTheReceiptsAndALaterOpenDeletesThemAndSettles() = runTest {
        val receipt = "$finals/$thing/$name${ArchiveLayout.LEGACY_RECEIPT_SUFFIX}"
        val prunedFile = "$finals-pruned/pruned.v1"
        write("$finals/$thing/$name", bytes)
        write(receipt, """{"size":${bytes.size},"crc":${ParquetVerify.crc32(bytes)},"sourceDevice":"dev"}""".encodeToByteArray())
        write(prunedFile, "pruned-v1\n$thing\tother.parquet\t46\t5\t11\n".encodeToByteArray())
        val mark = PrunedFile(thing, "other.parquet", 46, 5, 11)

        IosArchiveDatabase(store, dir).apply {
            assertEquals(listOf(name), index.observeAll().first().map { it.name })
            assertEquals(listOf(mark), pruned.observeAll().first())
            close()
        }
        assertTrue(fm.fileExistsAtPath("$dir/${AppDatabase.FILE_NAME}"))
        assertTrue(fm.fileExistsAtPath("$dir/${IosArchiveDatabase.IMPORT_MARKER}"))
        assertTrue(fm.fileExistsAtPath(receipt))
        assertTrue(fm.fileExistsAtPath(prunedFile))

        // A later launch reads the same rows from the file, deletes the old files and replaces nothing.
        write(receipt, """{"size":${bytes.size},"crc":${ParquetVerify.crc32(bytes)},"sourceDevice":"later"}""".encodeToByteArray())
        IosArchiveDatabase(store, dir).apply {
            assertEquals("dev", index.get(ArchiveKey(thing, name))?.sourceDevice)
            assertEquals(listOf(mark), pruned.forThing(thing))
            close()
        }
        assertFalse(fm.fileExistsAtPath(receipt))
        assertFalse(fm.fileExistsAtPath("$finals-pruned"))
        assertTrue(fm.fileExistsAtPath("$dir/${IosArchiveDatabase.IMPORT_SETTLED}"))
    }

    @Test
    fun aBusyCheckpointWritesNoMarkerAndDeletesNothing() = runTest {
        val receipt = "$finals/$thing/$name${ArchiveLayout.LEGACY_RECEIPT_SUFFIX}"
        write("$finals/$thing/$name", bytes)
        write(receipt, """{"size":${bytes.size},"crc":${ParquetVerify.crc32(bytes)}}""".encodeToByteArray())
        val path = "$dir/${AppDatabase.FILE_NAME}"
        openAppDatabase(path).apply {
            checkpointDurably()
            close()
        }
        // A reader on the snapshot before the import keeps a full checkpoint from finishing.
        val reader = BundledSQLiteDriver().open(path)
        try {
            reader.execSQL("BEGIN")
            reader.prepare("SELECT count(*) FROM archived_file").use { it.step() }

            IosArchiveDatabase(store, dir).apply {
                assertFails { index.get(ArchiveKey(thing, name)) }
                close()
            }
            assertFalse(fm.fileExistsAtPath("$dir/${IosArchiveDatabase.IMPORT_MARKER}"))
            assertTrue(fm.fileExistsAtPath(receipt))
        } finally {
            reader.close()
        }

        IosArchiveDatabase(store, dir).apply {
            assertEquals(listOf(name), index.observeAll().first().map { it.name })
            close()
        }
        assertTrue(fm.fileExistsAtPath("$dir/${IosArchiveDatabase.IMPORT_MARKER}"))
    }
}

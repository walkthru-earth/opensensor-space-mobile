@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import space.opensensor.data.protocol.ParquetVerify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun tempRoot(): String = NSTemporaryDirectory().trimEnd('/') + "/archive-test-" + NSUUID().UUIDString()

class IosArchiveStoreContractTest : ArchiveStoreContract() {
    private val base = tempRoot()

    override fun newStore(): ArchiveStore = IosArchiveStore("$base/SensorArchive", "$base/archive-partial", Dispatchers.Unconfined)
    override fun newIndex(): ArchiveIndex = InMemoryArchiveIndex()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }
}

/** What the iPhone archive written by earlier builds needs from the shared store. */
class IosArchiveLegacyTest {
    private val base = tempRoot()
    private val finals = "$base/SensorArchive"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Unconfined)
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val key = ArchiveKey(thing, "thing=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet")
    private val bytes = ParquetVerify.MAGIC + ByteArray(40) { it.toByte() } + ParquetVerify.MAGIC
    private val meta = PartialMeta(bytes.size.toLong(), ParquetVerify.crc32(bytes))
    private val dir get() = "$finals/$thing/" + key.name.substringBeforeLast('/')
    private val final get() = "$finals/$thing/${key.name}"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun writeFile(path: String, content: ByteArray) {
        IosFiles.mkdirs(path.substringBeforeLast('/'))
        IosFiles.writeAtomically(path, content)
    }

    @Test
    fun aLegacyPartialWithTheSameMetaIsAdoptedAndResumes() = runTest {
        writeFile("$final.${meta.size}-${meta.crc32.toString(16)}.partial", bytes.copyOfRange(0, 10))
        writeFile("$final.99-abc.partial", ByteArray(3))
        assertEquals(mapOf(key.name to 10L), store.partialLengths(thing))
        store.openPartial(key, meta).use { w ->
            assertEquals(10, w.resumeFrom)
            w.writeAt(10, bytes.copyOfRange(10, bytes.size))
        }
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind("$final.99-abc.partial"))
        assertEquals(ParquetVerify.Result.Ok, ArchiveVerifier.verify(store.reader(key, partial = true), meta))
        store.commit(key)
        assertTrue(bytes.contentEquals(store.reader(key).read(0, bytes.size)))
        assertEquals(listOf(key), store.listFinal())
    }

    @Test
    fun aSymlinkInTheArchiveIsRefused() = runTest {
        IosFiles.mkdirs("$finals/$thing")
        IosFiles.mkdirs("$base/elsewhere")
        NSFileManager.defaultManager.createSymbolicLinkAtPath("$finals/$thing/thing=$thing", "$base/elsewhere", null)
        assertFailsWith<IllegalStateException> { store.exists(key) }
    }
}

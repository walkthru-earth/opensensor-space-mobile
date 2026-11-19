@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS daily export lists `derived/v1` under the same zip names as Android (#301). */
class IosDailyFilesTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/daily-files-test-" + NSUUID().UUIDString()
    private val derived = "$base/${ArchiveLayout.DERIVED}"
    private val a = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val b = "0b1d6c55-55a4-4c4b-9e0f-0b6f4a2f1c11"
    private val day = 20_400L

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun write(relative: String, text: String) {
        val path = "$base/$relative"
        IosFiles.mkdirs(path.substringBeforeLast('/'))
        IosFiles.writeAtomically(path, text.encodeToByteArray())
    }

    private fun dayFile(thing: String) = write(ArchiveLayout.derivedDayPath(thing, day), "PAR1 $thing")

    private fun manifest(thing: String) = write(ArchiveLayout.derivedManifestPath(thing, day), "{}")

    @Test
    fun entriesCarryTheAndroidZipNames() {
        dayFile(a)
        manifest(a)
        val entries = ArchiveExport.dailyEntries(directoryEntries(derived, ArchiveLayout.DERIVED), setOf(a))!!
        assertEquals(
            listOf(ArchiveLayout.derivedManifestPath(a, day), ArchiveLayout.derivedDayPath(a, day)),
            entries.map { it.path },
        )
        assertTrue(entries.all { it.path.startsWith("derived/v1/$a/day=") })
        entries.first { it.path.endsWith(".parquet") }.open().use { reader ->
            assertContentEquals("PAR1 $a".encodeToByteArray(), reader.read(0, reader.size.toInt()))
        }
    }

    @Test
    fun aThingWithNoDayFileGivesNoDailyZip() {
        dayFile(a)
        manifest(a)
        // B's day was evicted, its manifest stayed.
        manifest(b)
        IosFiles.mkdirs("$base/${ArchiveLayout.derivedThingDir(b)}/empty")
        val listed = directoryEntries(derived, ArchiveLayout.DERIVED)
        assertNull(ArchiveExport.dailyEntries(listed, setOf(b)))
        assertEquals(3, ArchiveExport.dailyEntries(listed, setOf(a, b))?.size)
    }

    @Test
    fun noDerivedFolderListsNothing() {
        assertEquals(emptyList(), directoryEntries(derived, ArchiveLayout.DERIVED))
    }

    @Test
    fun aLinkIsNeverListed() {
        dayFile(a)
        val target = "$base/elsewhere.parquet"
        IosFiles.writeAtomically(target, "PAR1 secret".encodeToByteArray())
        val link = "$base/${ArchiveLayout.derivedThingDir(a)}/day=2025-01-01.parquet"
        assertTrue(NSFileManager.defaultManager.createSymbolicLinkAtPath(link, target, null))
        assertEquals(listOf(ArchiveLayout.derivedDayPath(a, day)), directoryEntries(derived, ArchiveLayout.DERIVED).map { it.path })
    }

    @Test
    fun aDayFileGoneAfterTheListingIsLeftOutOfTheZip() {
        dayFile(a)
        dayFile(b)
        val entries = ArchiveExport.dailyEntries(directoryEntries(derived, ArchiveLayout.DERIVED), setOf(a, b))!!
        IosFiles.remove("$base/${ArchiveLayout.derivedDayPath(b, day)}")
        assertFailsWith<ArchiveFileMissingException> { entries.first { b in it.path }.open() }
        assertEquals(1, ArchiveExport.writeZip(MemorySink(), entries))
    }

    @Test
    fun forgettingEvictedDaysDropsOnlyManifestsWithoutTheirDayFile() {
        dayFile(a)
        manifest(a)
        manifest(b)
        assertEquals(1, forgetEvictedDays(derived))
        assertEquals(IosFiles.Kind.FILE, IosFiles.kind("$base/${ArchiveLayout.derivedManifestPath(a, day)}"))
        assertEquals(IosFiles.Kind.ABSENT, IosFiles.kind("$base/${ArchiveLayout.derivedManifestPath(b, day)}"))
        assertEquals(0, forgetEvictedDays(derived))
    }
}

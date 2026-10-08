package earth.walkthru.aqsync.archive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveLayoutTest {
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dated = "station=$station/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
    private val unsynced = "station=$station/unsynced/boot=x/data_unsynced_x_0-89-0.parquet"
    private val bench = "benchmarks/codec/lz4.parquet"

    @Test
    fun `final and partial paths keep the Hive name verbatim below the station`() {
        val key = ArchiveKey(station, dated)
        assertEquals("archive/$station/$dated", ArchiveLayout.finalPath(key))
        assertEquals("archive-partial/$station/$dated.partial", ArchiveLayout.partialPath(key))
        assertEquals("archive-partial/$station/$dated.partial.meta", ArchiveLayout.partialMetaPath(key))
        assertEquals("archive/$station/$unsynced", ArchiveLayout.finalPath(ArchiveKey(station, unsynced)))
        assertEquals("archive/$station/$bench", ArchiveLayout.finalPath(ArchiveKey(station, bench)))
        assertEquals("archive/$station", ArchiveLayout.stationDir(station))
        assertEquals("archive-partial/$station", ArchiveLayout.partialStationDir(station))
    }

    @Test
    fun `derived day paths use the UTC day label`() {
        assertEquals("2026-09-17", ArchiveLayout.dayLabel(20713))
        assertEquals("1970-01-01", ArchiveLayout.dayLabel(0))
        assertEquals("1969-12-31", ArchiveLayout.dayLabel(-1))
        assertEquals("derived/v1/$station/day=2026-09-17.parquet", ArchiveLayout.derivedDayPath(station, 20713))
        assertEquals("derived/v1/$station/day=2026-09-17.manifest.json", ArchiveLayout.derivedManifestPath(station, 20713))
    }

    @Test
    fun `station ids are one safe directory name and may contain a dot`() {
        for (ok in listOf(station, "AQ-6b40", "board.v2", "a_b", "x")) assertTrue(ArchiveLayout.isSafeStation(ok), ok)
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a b", "a\u0000b", "é", "a:b")) assertFalse(ArchiveLayout.isSafeStation(bad), bad)
        assertFailsWith<IllegalArgumentException> { ArchiveKey("..", dated) }
        assertFailsWith<IllegalArgumentException> { ArchiveLayout.stationDir("a/b") }
    }

    @Test
    fun `unsafe names are refused like the iOS archive refuses them`() {
        // The list from ios/tests/ArchiveChecks.swift.
        val unsafe = listOf(
            "../escape.parquet", "/escape.parquet", "a//b.parquet", "a/./b.parquet", "a\\b.parquet", "a\u0000b.parquet",
            "UPPER.parquet", ".parquet", "a..b.parquet", "a".repeat(392) + ".parquet",
            "./a.parquet", "$dated.partial", "$dated.verified.json",
        )
        for (name in unsafe) {
            assertFalse(ArchiveLayout.isArchiveName(name), name)
            assertFailsWith<IllegalArgumentException> { ArchiveKey(station, name) }
        }
        // Exactly 399 bytes is the contract maximum.
        assertTrue(ArchiveLayout.isArchiveName("a".repeat(391) + ".parquet"))
    }

    @Test
    fun `partial names come back only from partial files`() {
        assertEquals(dated, ArchiveLayout.nameOfPartial("$dated.partial"))
        assertNull(ArchiveLayout.nameOfPartial("$dated.partial.meta"))
        assertNull(ArchiveLayout.nameOfPartial(dated))
        // The iOS partial name carries size and CRC and is not a partial of this layout.
        assertNull(ArchiveLayout.nameOfPartial("$dated.1234-abcd.partial"))
    }

    @Test
    fun `partial meta round trips and malformed meta is refused`() {
        val meta = PartialMeta(41_993, 0xFFFF_FFFFL)
        assertEquals("41993 4294967295", ArchiveLayout.encodeMeta(meta))
        assertEquals(meta, ArchiveLayout.decodeMeta("41993 4294967295\n"))
        for (bad in listOf("", "41993", "41993 ", "a 1", "1 b", "-1 2", "1 -2", "1 4294967296", "1 2 3", "1  2")) {
            assertNull(ArchiveLayout.decodeMeta(bad), bad)
        }
    }
}

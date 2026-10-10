package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

/**
 * Truncated and corrupted files through both readers. The contract: [ParquetFile.wrap] plus reading every
 * column, and [ParquetReader.open] plus a scan of every column (and a filtered scan on an INT64 column),
 * either succeed or throw [ParquetFormatException]. Any other throwable is a bug; every case that throws
 * one is collected and reported in one message naming the fixture, the corruption and the stack top.
 *
 * Corruptions are deterministic (seeded [Random]). [SCALE] sets how many random cases run per fixture;
 * the iOS simulator runs a prefix of the JVM's cases, since a debug native binary decodes far slower.
 */
class TruncatedFooterFuzzTest {

    private class Fixture(val path: String) {
        val bytes: ByteArray = fixtureBytes(path)
        val metadata: ParquetFileMetadata = ParquetFooterParser.parse(bytes)
        val names: List<String> = metadata.leaves.map { it.name }
        val dataEnd: Int = bytes.size - 8 - metadata.footerLength

        /** An INT64 column and a one-value range around a value it holds, for the filtered scan. */
        val filter: Pair<String, LongRange>? = metadata.leaves.firstOrNull { it.type == ParquetType.INT64 }?.let { leaf ->
            val values = ParquetFile.wrap(bytes, path, testCodecs).readColumn(leaf.name) as ColumnValues.Int64
            val defined = values.values.indices.filter { !values.isNull(it) }
            if (defined.isEmpty()) null else values.values[defined[defined.size / 2]].let { v -> leaf.name to (v..v) }
        }

        /** File bytes of every page index of the file, or null when it has none. */
        val pageIndex: IntRange? = metadata.rowGroups.flatMap { rg -> rg.columns.flatMap { listOfNotNull(it.offsetIndex, it.columnIndex) } }
            .takeIf { it.isNotEmpty() }
            ?.let { r -> r.minOf { it.first }.toInt()..r.maxOf { it.last }.toInt() }
    }

    private val fixtures: List<Fixture> by lazy {
        FIXTURES.map { Fixture(it) }
    }

    private class Failure(val case: String, val error: Throwable) {
        override fun toString(): String {
            val frames = error.stackTraceToString().lines().map { it.trim() }.filter { it.startsWith("at ") }
            val top = frames.filter { "earth.walkthru" in it && "FuzzTest" !in it }.ifEmpty { frames }.take(3)
            return "$case -> ${error::class.simpleName}: ${error.message}\n      ${top.joinToString("\n      ")}"
        }
    }

    private val failures = ArrayList<Failure>()
    private var cases = 0

    /** Runs [block], accepting success or [ParquetFormatException] and recording anything else. */
    private inline fun expectClean(case: String, block: () -> Unit) {
        cases++
        try {
            block()
        } catch (_: ParquetFormatException) {
            // The contract.
        } catch (e: Throwable) {
            failures += Failure(case, e)
        }
    }

    private suspend fun exercise(f: Fixture, bytes: ByteArray, case: String) {
        expectClean("$case [ParquetFile]") {
            val pf = ParquetFile.wrap(bytes, f.path, testCodecs)
            for (leaf in pf.leaves) pf.readColumn(leaf)
        }
        expectClean("$case [ParquetReader scan]") {
            ParquetReader.open(MemoryRangeSource(bytes, f.path), testCodecs).use { r -> r.scan(ScanRequest(f.names)) }
        }
        val filter = f.filter ?: return
        expectClean("$case [ParquetReader filter ${filter.first} in ${filter.second}]") {
            ParquetReader.open(MemoryRangeSource(bytes, f.path), testCodecs).use { r ->
                r.scan(ScanRequest(f.names, filterColumn = filter.first, filterRanges = listOf(filter.second)))
            }
        }
    }

    private fun report() {
        if (failures.isEmpty()) return
        val shown = failures.take(MAX_REPORTED).joinToString("\n  ")
        val more = if (failures.size > MAX_REPORTED) "\n  ... and ${failures.size - MAX_REPORTED} more" else ""
        fail("${failures.size} of $cases cases threw something other than ParquetFormatException:\n  $shown$more")
    }

    @Test
    fun `the clean fixtures read without error`() = runTest(timeout = 5.minutes) {
        for (f in fixtures) {
            ParquetFile.wrap(f.bytes, f.path, testCodecs).let { pf -> for (leaf in pf.leaves) pf.readColumn(leaf) }
            ParquetReader.open(MemoryRangeSource(f.bytes, f.path), testCodecs).use { r ->
                assertEquals(f.path, f.metadata.numRows.toInt(), r.scan(ScanRequest(f.names)).rowCount)
                f.filter?.let { (name, range) ->
                    val batch = r.scan(ScanRequest(f.names, filterColumn = name, filterRanges = listOf(range)))
                    assertTrue("${f.path} filter keeps the row it was built from", batch.rowCount >= 1)
                }
            }
        }
        assertTrue("the discover fixture has a page index", fixtures.first().pageIndex != null)
    }

    @Test
    fun `every cut in the last 512 bytes is refused cleanly`() = runTest(timeout = 5.minutes) {
        for (f in fixtures) {
            val size = f.bytes.size
            for (length in maxOf(0, size - 512) until size) exercise(f, f.bytes.copyOf(length), "${f.path} cut to $length of $size")
        }
        report()
    }

    @Test
    fun `random cuts are refused cleanly`() = runTest(timeout = 5.minutes) {
        val random = Random(20261011)
        for (f in fixtures) {
            val size = f.bytes.size
            repeat(40 * SCALE) {
                val length = random.nextInt(size)
                exercise(f, f.bytes.copyOf(length), "${f.path} cut to $length of $size")
            }
        }
        report()
    }

    @Test
    fun `random byte flips in the footer either parse or are refused cleanly`() = runTest(timeout = 5.minutes) {
        val random = Random(4242)
        for (f in fixtures) {
            val footerStart = f.dataEnd
            val footerEnd = f.bytes.size - 8
            repeat(60 * SCALE) {
                val at = random.nextInt(footerStart, footerEnd)
                val value = random.nextInt(256)
                if (value.toByte() == f.bytes[at]) return@repeat
                val bytes = f.bytes.copyOf().also { it[at] = value.toByte() }
                exercise(f, bytes, "${f.path} footer byte $at (footer $footerStart..<$footerEnd) ${f.bytes[at].toInt() and 0xff} -> $value")
            }
            // Several bytes at once, which reaches deeper into the Thrift structs than one flip.
            repeat(15 * SCALE) {
                val bytes = f.bytes.copyOf()
                val changed = (1..random.nextInt(2, 6)).map { random.nextInt(footerStart, footerEnd) to random.nextInt(256) }
                for ((at, value) in changed) bytes[at] = value.toByte()
                exercise(f, bytes, "${f.path} footer bytes ${changed.joinToString { (a, v) -> "$a->$v" }}")
            }
        }
        report()
    }

    @Test
    fun `a wrong footer length is refused cleanly`() = runTest(timeout = 5.minutes) {
        for (f in fixtures) {
            val size = f.bytes.size
            val real = f.metadata.footerLength
            val lengths = listOf(
                0, -1, -real, Int.MIN_VALUE, Int.MAX_VALUE, size, size - 12, size - 11, size - 8,
                real - 1, real + 1, real / 2, real * 2, 1, 4, 64 * 1024 + 1,
            ).distinct()
            for (length in lengths) {
                val bytes = f.bytes.copyOf()
                writeIntLe(bytes, size - 8, length)
                exercise(f, bytes, "${f.path} footer length $real -> $length")
            }
            // The magic itself, at either end.
            for (at in listOf(0, 3, size - 4, size - 1)) {
                val bytes = f.bytes.copyOf().also { it[at] = 'X'.code.toByte() }
                exercise(f, bytes, "${f.path} magic byte $at")
            }
        }
        report()
    }

    @Test
    fun `random bytes in the page index are refused cleanly`() = runTest(timeout = 5.minutes) {
        val random = Random(7_062)
        var withIndex = 0
        for (f in fixtures) {
            val region = f.pageIndex ?: continue
            withIndex++
            repeat(60 * SCALE) {
                val bytes = f.bytes.copyOf()
                val changed = (1..random.nextInt(1, 9)).map { random.nextInt(region.first, region.last + 1) to random.nextInt(256) }
                for ((at, value) in changed) bytes[at] = value.toByte()
                exercise(f, bytes, "${f.path} page index ($region) bytes ${changed.joinToString { (a, v) -> "$a->$v" }}")
            }
            // A run of noise over a whole stretch of the index.
            repeat(10 * SCALE) {
                val bytes = f.bytes.copyOf()
                val start = random.nextInt(region.first, region.last + 1)
                val end = minOf(region.last + 1, start + random.nextInt(1, 64))
                for (at in start until end) bytes[at] = random.nextInt(256).toByte()
                exercise(f, bytes, "${f.path} page index noise $start..<$end (seed 7062)")
            }
        }
        assertTrue("some fixture has a page index", withIndex > 0)
        report()
    }

    /**
     * A footer whose `FileMetaData.num_rows` or `RowGroup.num_rows` is negative once reached `ColumnSink`
     * and allocated a negative-size array. The parser now refuses it. Both cases are one byte of the
     * discover fixture: the zigzag varint 190 (`fc 02`) becomes -192 (`ff 02`).
     */
    @Test
    fun `a negative row count in the footer is refused cleanly`() = runTest(timeout = 5.minutes) {
        val f = fixtures.first()
        for ((at, field) in listOf(24792 to "footer declares -192 rows", 27379 to "row group 0 declares -192 rows")) {
            val bytes = f.bytes.copyOf().also { it[at] = 0xff.toByte() }
            val e = assertFailsWith<ParquetFormatException> { ParquetFooterParser.parse(bytes) }
            assertTrue(e.message, e.message!!.contains(field))
            assertFailsWith<ParquetFormatException> { ParquetFile.wrap(bytes, f.path) }
            assertFailsWith<ParquetFormatException> { ParquetReader.open(MemoryRangeSource(bytes, f.path)) }
        }
    }

    private fun writeIntLe(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private companion object {
        /** Random cases per fixture scale with this: 15 on the JVM, 3 on the iOS simulator. */
        val SCALE = fuzzScale
        const val MAX_REPORTED = 40

        val FIXTURES = listOf(
            "parquet/discover/cells-24h-res7-pageindex.parquet",
            "parquet/device/v1-lz4-90rows-dated.parquet",
            "parquet/device/v2-lz4-4rows-dated.parquet",
            "parquet/device/v3-board-2rg-105rows.parquet",
            "parquet/v3/v3-max-groups-lz4.parquet",
            "parquet/v3/v3-empty-none.parquet",
            "geoparquet/pyarrow/pa-snappy-dict-v1.parquet",
        )
    }
}

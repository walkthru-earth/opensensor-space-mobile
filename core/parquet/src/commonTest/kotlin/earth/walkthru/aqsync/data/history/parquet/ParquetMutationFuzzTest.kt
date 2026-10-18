package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Mutates bytes anywhere in real Parquet fixtures, the data pages included, with a fixed seed (#97).
 * Every mutated file either reads or throws [ParquetFormatException], through [ParquetFile] and through a
 * [ParquetReader] scan. Any other throwable is a bug. A hang fails on the timeout, and a huge allocation
 * shows up as an [OutOfMemoryError], which is reported like any other throwable.
 *
 * [TruncatedFooterFuzzTest] covers cuts and the footer. This one reaches page headers, dictionary pages,
 * definition levels and the DELTA and BYTE_STREAM_SPLIT decoders. It stays well under 10 seconds.
 */
class ParquetMutationFuzzTest {

    private class Fixture(val path: String) {
        val bytes: ByteArray = fixtureBytes(path)
        val names: List<String> = ParquetFooterParser.parse(bytes).leaves.map { it.name }
    }

    private val failures = ArrayList<String>()
    private var cases = 0

    private suspend fun exercise(f: Fixture, bytes: ByteArray, case: String) {
        cases++
        try {
            val pf = ParquetFile.wrap(bytes, f.path, testCodecs)
            for (leaf in pf.leaves) pf.readColumn(leaf)
        } catch (_: ParquetFormatException) {
        } catch (e: Throwable) {
            failures += "$case [ParquetFile] -> ${e::class.simpleName}: ${e.message}"
        }
        try {
            ParquetReader.open(MemoryRangeSource(bytes, f.path), testCodecs).use { it.scan(ScanRequest(f.names)) }
        } catch (_: ParquetFormatException) {
        } catch (e: Throwable) {
            failures += "$case [ParquetReader] -> ${e::class.simpleName}: ${e.message}"
        }
    }

    @Test
    fun `mutated fixtures only ever fail as a format error`() = runTest(timeout = 60.seconds) {
        val random = Random(97)
        for (path in FIXTURES) {
            val f = Fixture(path)
            val size = f.bytes.size
            repeat(CASES_PER_FIXTURE * fuzzScale / 15) { n ->
                val bytes = f.bytes.copyOf()
                val what = when (n % 4) {
                    0 -> {
                        // One byte anywhere past the head magic.
                        val at = random.nextInt(4, size - 8)
                        bytes[at] = random.nextInt(256).toByte()
                        "byte $at"
                    }
                    1 -> {
                        // One bit, which keeps most of a varint or a length intact.
                        val at = random.nextInt(4, size - 8)
                        val bit = random.nextInt(8)
                        bytes[at] = (bytes[at].toInt() xor (1 shl bit)).toByte()
                        "bit $bit of $at"
                    }
                    2 -> {
                        // A few scattered bytes.
                        val changed = (1..random.nextInt(2, 8)).map { random.nextInt(4, size - 8) }
                        for (at in changed) bytes[at] = random.nextInt(256).toByte()
                        "bytes $changed"
                    }
                    else -> {
                        // A short run of 0x00, 0xFF or noise, the shapes that stretch varints and sizes.
                        val start = random.nextInt(4, size - 8)
                        val end = minOf(size - 8, start + random.nextInt(1, 24))
                        val fill = random.nextInt(3)
                        for (at in start until end) bytes[at] = when (fill) { 0 -> 0; 1 -> -1; else -> random.nextInt(256).toByte() }
                        "run $start..<$end fill $fill"
                    }
                }
                exercise(f, bytes, "$path $what")
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of $cases cases threw something other than ParquetFormatException:\n  ${failures.take(30).joinToString("\n  ")}")
    }

    private companion object {
        /** On the JVM. The iOS simulator runs a fifth of them, see [fuzzScale]. */
        const val CASES_PER_FIXTURE = 1000

        val FIXTURES = listOf(
            "parquet/discover/cells-24h-res7-pageindex.parquet",
            "parquet/device/v1-lz4-90rows-dated.parquet",
            "parquet/device/v3-board-2rg-105rows.parquet",
            "parquet/v3/v3-max-groups-none.parquet",
            "geoparquet/pyarrow/pa-snappy-dict-v1.parquet",
            "parquet/encodings/pyarrow-none-v1.parquet",
            "parquet/encodings/pyarrow-none-v2.parquet",
            "parquet/encodings/duckdb-uncompressed.parquet",
            "parquet/encodings/duckdb-snappy.parquet",
        )
    }
}

package space.opensensor.data.history.parquet

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** [LocalRangeSource] against a temporary file: positional reads, bounds, identity and close, every platform. */
class LocalRangeSourceTest {

    private val data = Random(7).nextBytes(4096)

    private fun source(bytes: ByteArray = data): LocalRangeSource = LocalRangeSource(tempLocalFile("range", bytes))

    @Test
    fun `tail reads return the last n bytes and clamp to the file`() = runTest {
        source().use { s ->
            assertContentEquals(data.copyOfRange(4096 - 100, 4096), s.readTail(100).toByteArray())
            assertContentEquals(data, s.readTail(10_000).toByteArray())
            assertContentEquals(data, s.readTail(4096).toByteArray())
            assertEquals(0, s.readTail(0).length)
        }
    }

    @Test
    fun `ranges come back in input order with empty ranges as the empty view`() = runTest {
        source().use { s ->
            val ranges = listOf(4000L until 4096, 0L until 16, 100L until 100, 16L until 1016, 0L until 4096)
            val views = s.readRanges(ranges)
            assertEquals(ranges.size, views.size)
            ranges.forEachIndexed { i, r ->
                val expected = if (r.isEmpty()) ByteArray(0) else data.copyOfRange(r.first.toInt(), r.last.toInt() + 1)
                assertContentEquals(expected, views[i].toByteArray(), "range $i $r")
            }
            assertSame(ByteView.EMPTY, views[2])
        }
    }

    @Test
    fun `coalesced local reads match the raw reads`() = runTest {
        source().use { s ->
            val ranges = listOf(300L until 400, 0L until 100, 410L until 500, 3000L until 3010)
            val raw = s.readRanges(ranges).map { it.toByteArray() }
            val counting = CountingRangeSource(s)
            val merged = CoalescingRangeSource(counting, RangePolicy.LOCAL).readRanges(ranges)
            raw.forEachIndexed { i, b -> assertContentEquals(b, merged[i].toByteArray()) }
            assertEquals(1, counting.requests)
        }
    }

    @Test
    fun `ranges outside the file are refused`() = runTest {
        source().use { s ->
            assertFailsWith<IndexOutOfBoundsException> { s.readRanges(listOf(4000L until 4097)) }
            assertFailsWith<IndexOutOfBoundsException> { s.readRanges(listOf(4096L until 4097)) }
            assertFailsWith<IndexOutOfBoundsException> { s.readRanges(listOf(-1L until 10)) }
        }
    }

    @Test
    fun `id carries the size and a version that changes when the file is rewritten`() = runTest {
        val file = tempLocalFile("range-id", data)
        val first = LocalRangeSource(file).use { it.id() }
        assertEquals(4096L, first.size)
        assertTrue(first.version.isNotEmpty())
        assertTrue(first.uri.isNotEmpty())
        writeLocalFile(file, data.copyOfRange(0, 1000))
        val second = LocalRangeSource(file).use { it.id() }
        assertEquals(1000L, second.size)
        assertNotEquals(first.version, second.version)
        assertEquals(first.uri, second.uri)
    }

    @Test
    fun `an empty file serves an empty tail`() = runTest {
        source(ByteArray(0)).use { s ->
            assertEquals(0L, s.id().size)
            assertEquals(0, s.readTail(64).length)
            assertSame(ByteView.EMPTY, s.readRanges(listOf(LongRange.EMPTY)).single())
        }
    }

    @Test
    fun `closing twice does not crash`() {
        val s = source()
        s.close()
        s.close()
    }
}

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [RangePlanner] merges by gap, caps merged requests and never cuts a range; [CoalescingRangeSource]
 * returns the same bytes as naive slicing, in input order, with bounded parallelism. Memory only.
 */
class RangePlannerTest {

    private fun policy(gap: Long, max: Long, parallelism: Int = 1) =
        RangePolicy(gap, max, parallelism, wholeFileBelowBytes = 0, tailPrefetchBytes = 8)

    private fun bytes(size: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(size)

    private fun expected(bytes: ByteArray, range: LongRange): ByteArray =
        if (range.isEmpty()) ByteArray(0) else bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1)

    /** Delays every read so concurrent callers overlap under the test dispatcher. */
    private class SlowRangeSource(private val inner: RangeSource) : RangeSource {
        override suspend fun id(): SourceId = inner.id()
        override suspend fun readTail(n: Int): ByteView = inner.readTail(n)
        override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> {
            delay(10)
            return inner.readRanges(ranges)
        }
    }

    @Test
    fun `a gap exactly at the policy gap merges and one byte more does not`() {
        val p = policy(gap = 10, max = 1000)
        val merged = RangePlanner.plan(listOf(0L until 10, 20L until 30), p)
        assertEquals(listOf(0L until 30), merged.requests)
        val split = RangePlanner.plan(listOf(0L until 10, 21L until 31), p)
        assertEquals(listOf(0L until 10, 21L until 31), split.requests)
    }

    @Test
    fun `a merged group is cut so every request stays at or under the max`() {
        val plan = RangePlanner.plan(listOf(0L until 40, 50L until 90, 95L until 130), policy(gap = 1000, max = 100))
        assertEquals(listOf(0L until 90, 95L until 130), plan.requests)
        assertTrue(plan.requests.all { it.last - it.first + 1 <= 100 })
        assertEquals(0, plan.slices[0]!!.request)
        assertEquals(0, plan.slices[1]!!.request)
        assertEquals(50, plan.slices[1]!!.offset)
        assertEquals(1, plan.slices[2]!!.request)
    }

    @Test
    fun `a merged request of exactly the max is allowed`() {
        val plan = RangePlanner.plan(listOf(0L until 50, 60L until 100), policy(gap = 1000, max = 100))
        assertEquals(listOf(0L until 100), plan.requests)
    }

    @Test
    fun `a single range larger than the max stays one request`() {
        val p = policy(gap = 1000, max = 10)
        assertEquals(listOf(0L until 50), RangePlanner.plan(listOf(0L until 50), p).requests)
        val plan = RangePlanner.plan(listOf(0L until 5, 6L until 50, 52L until 55), p)
        assertEquals(listOf(0L until 5, 6L until 50, 52L until 55), plan.requests)
        assertEquals(44, plan.slices[1]!!.length)
    }

    @Test
    fun `slices follow input order whatever the file order`() = runTest {
        val data = bytes(100)
        val ranges = listOf(50L until 60, 0L until 10, 20L until 30)
        val plan = RangePlanner.plan(ranges, policy(gap = 100, max = 1000))
        assertEquals(listOf(0L until 60), plan.requests)
        assertEquals(listOf(50, 0, 20), plan.slices.map { it!!.offset })
        val views = CoalescingRangeSource(MemoryRangeSource(data), policy(gap = 100, max = 1000)).readRanges(ranges)
        ranges.forEachIndexed { i, r -> assertContentEquals(expected(data, r), views[i].toByteArray()) }
    }

    @Test
    fun `overlapping and duplicate ranges share one request`() = runTest {
        val data = bytes(64)
        val ranges = listOf(0L until 20, 10L until 30, 10L until 30, 5L until 15)
        val p = policy(gap = 0, max = 1000)
        assertEquals(listOf(0L until 30), RangePlanner.plan(ranges, p).requests)
        val views = CoalescingRangeSource(MemoryRangeSource(data), p).readRanges(ranges)
        ranges.forEachIndexed { i, r -> assertContentEquals(expected(data, r), views[i].toByteArray()) }
    }

    @Test
    fun `empty ranges get a null slice and the empty view`() = runTest {
        val ranges = listOf(5L until 5, 0L until 4, LongRange.EMPTY)
        val p = policy(gap = 0, max = 1000)
        val plan = RangePlanner.plan(ranges, p)
        assertNull(plan.slices[0])
        assertNotNull(plan.slices[1])
        assertNull(plan.slices[2])
        assertEquals(listOf(0L until 4), plan.requests)
        val views = CoalescingRangeSource(MemoryRangeSource(bytes(10)), p).readRanges(ranges)
        assertSame(ByteView.EMPTY, views[0])
        assertSame(ByteView.EMPTY, views[2])
        assertEquals(4, views[1].length)
        assertSame(ByteView.EMPTY, MemoryRangeSource(bytes(10)).readRanges(listOf(3L until 3)).single())
        assertTrue(CoalescingRangeSource(MemoryRangeSource(bytes(10)), p).readRanges(emptyList()).isEmpty())
    }

    @Test
    fun `a negative start is refused`() = runTest {
        val p = policy(gap = 0, max = 1000)
        assertFailsWith<IndexOutOfBoundsException> { RangePlanner.plan(listOf(0L until 4, -1L until 4), p) }
        assertFailsWith<IndexOutOfBoundsException> { CoalescingRangeSource(MemoryRangeSource(bytes(10)), p).readRanges(listOf(-3L until 2)) }
        assertFailsWith<IndexOutOfBoundsException> { MemoryRangeSource(bytes(10)).readRanges(listOf(-3L until 2)) }
    }

    @Test
    fun `a range past the end of the file is refused`() = runTest {
        val p = policy(gap = 0, max = 1000)
        assertFailsWith<IndexOutOfBoundsException> { CoalescingRangeSource(MemoryRangeSource(bytes(10)), p).readRanges(listOf(5L until 11)) }
        assertFailsWith<IndexOutOfBoundsException> { MemoryRangeSource(bytes(10)).readRanges(listOf(10L until 11)) }
    }

    @Test
    fun `coalesced reads equal naive slicing for random ranges and policies`() = runTest {
        val random = Random(62)
        repeat(400) { iteration ->
            val size = random.nextInt(0, 2000)
            val data = random.nextBytes(size)
            val p = policy(gap = random.nextLong(0, 200), max = random.nextLong(1, 500), parallelism = random.nextInt(1, 5))
            val ranges = List(random.nextInt(0, 30)) {
                if (random.nextInt(8) == 0) {
                    LongRange.EMPTY
                } else {
                    val start = random.nextInt(0, size + 1)
                    val maxLength = size - start
                    val length = if (random.nextInt(4) == 0) maxLength else random.nextInt(0, minOf(maxLength, 300) + 1)
                    start.toLong() until start.toLong() + length
                }
            }
            val counting = CountingRangeSource(MemoryRangeSource(data))
            val views = CoalescingRangeSource(counting, p).readRanges(ranges)
            assertEquals("iteration $iteration count", ranges.size, views.size)
            ranges.forEachIndexed { i, r ->
                assertContentEquals(expected(data, r), views[i].toByteArray(), "iteration $iteration range $i $r")
            }
            val plan = RangePlanner.plan(ranges, p)
            assertEquals("iteration $iteration requests", plan.requests.size, counting.requests)
            plan.requests.forEachIndexed { q, request ->
                val length = request.last - request.first + 1
                if (length > p.maxRequestBytes) {
                    val members = ranges.indices.filter { plan.slices[it]?.request == q }
                    assertEquals("iteration $iteration oversized request $q members", 1, members.size)
                    assertEquals("iteration $iteration oversized request $q", ranges[members.single()], request)
                }
            }
        }
    }

    @Test
    fun `parallelism 2 never runs more than 2 inner calls at once`() = runTest {
        val data = bytes(10_000)
        val counting = CountingRangeSource(SlowRangeSource(MemoryRangeSource(data)))
        val source = CoalescingRangeSource(counting, policy(gap = 0, max = 1000, parallelism = 2))
        val ranges = List(10) { i -> (i * 1000L) until (i * 1000L + 100) }
        val views = source.readRanges(ranges)
        ranges.forEachIndexed { i, r -> assertContentEquals(expected(data, r), views[i].toByteArray()) }
        assertEquals(10, counting.rangeCalls)
        assertEquals(10, counting.requests)
        assertEquals(2, counting.maxConcurrent)
    }

    @Test
    fun `local policy reads adjacent column chunks in one inner request`() = runTest {
        val data = bytes(256 * 1024)
        val counting = CountingRangeSource(MemoryRangeSource(data))
        val source = CoalescingRangeSource(counting, RangePolicy.LOCAL)
        // Eight 16 KiB column chunks, each followed by a 100 byte gap.
        val ranges = List(8) { i -> byteRange(i * (16L * 1024 + 100), 16 * 1024) }.reversed()
        val views = source.readRanges(ranges)
        ranges.forEachIndexed { i, r -> assertContentEquals(expected(data, r), views[i].toByteArray()) }
        assertEquals(1, counting.rangeCalls)
        assertEquals(1, counting.requests)
        assertEquals(0, counting.tailReads)
    }

    @Test
    fun `memory views share the input array without copying`() = runTest {
        val data = bytes(100)
        val memory = MemoryRangeSource(data)
        val views = memory.readRanges(listOf(10L until 20, 0L until 100))
        assertSame(data, views[0].array)
        assertEquals(10, views[0].offset)
        assertSame(data, views[1].array)
        assertSame(data, memory.readTail(8).array)
        val coalesced = CoalescingRangeSource(memory, policy(gap = 100, max = 1000)).readRanges(listOf(30L until 40, 50L until 60))
        assertTrue(coalesced.all { it.array === data })
    }

    @Test
    fun `memory tail reads clamp to the file`() = runTest {
        val data = bytes(50)
        val memory = MemoryRangeSource(data, uri = "mem", version = "v1")
        assertContentEquals(data.copyOfRange(40, 50), memory.readTail(10).toByteArray())
        assertContentEquals(data, memory.readTail(500).toByteArray())
        assertEquals(0, memory.readTail(0).length)
        assertEquals(SourceId("mem", 50, "v1"), memory.id())
    }
}

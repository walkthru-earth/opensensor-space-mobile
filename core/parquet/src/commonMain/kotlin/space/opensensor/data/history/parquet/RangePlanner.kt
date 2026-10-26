package space.opensensor.data.history.parquet

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns the ranges a reader wants into the requests a source should make. Pure and deterministic.
 *
 * Non-empty ranges are sorted by start and merged while the next one starts within
 * [RangePolicy.coalesceGapBytes] of the current request's end and the merged request stays at or under
 * [RangePolicy.maxRequestBytes]. A single range larger than the cap stays one request of its own: it is
 * never cut, because every input range must come back as one contiguous [ByteView]. Overlapping and
 * duplicate ranges share bytes.
 */
object RangePlanner {
    /** Where input range [index] lies: `[offset, offset + length)` of request [request]. */
    class Slice(val request: Int, val offset: Int, val length: Int)

    /** [requests] in file order, and one [Slice] per input range (null for an empty range). */
    class Plan(val requests: List<LongRange>, val slices: List<Slice?>)

    fun plan(ranges: List<LongRange>, policy: RangePolicy): Plan {
        val order = ranges.indices.filter { !ranges[it].isEmpty() }.sortedBy { ranges[it].first }
        val requests = ArrayList<LongRange>()
        val slices = arrayOfNulls<Slice>(ranges.size)
        var start = 0L
        var end = 0L // exclusive
        val members = ArrayList<Int>()
        fun close() {
            if (members.isEmpty()) return
            val request = requests.size
            requests += start until end
            for (i in members) slices[i] = Slice(request, (ranges[i].first - start).toInt(), rangeLength(ranges[i]))
            members.clear()
        }
        for (i in order) {
            val r = ranges[i]
            if (r.first < 0) throw ParquetBoundsException("range $r starts before the file")
            rangeLength(r)
            val rEnd = r.last + 1
            if (members.isNotEmpty() && r.first <= end + policy.coalesceGapBytes && maxOf(end, rEnd) - start <= policy.maxRequestBytes) {
                end = maxOf(end, rEnd)
            } else {
                close()
                start = r.first
                end = rEnd
            }
            members += i
            if (end - start > Int.MAX_VALUE) throw ParquetBoundsException("request [$start, $end) is longer than 2 GiB")
        }
        close()
        return Plan(requests, slices.toList())
    }
}

/**
 * Plans every [readRanges] call with [RangePlanner] and fetches the merged requests from [inner], at
 * most [RangePolicy.parallelism] at a time, then hands back views into the fetched buffers in input
 * order. Nothing is copied: a view shares its request's buffer.
 */
class CoalescingRangeSource(private val inner: RangeSource, val policy: RangePolicy) : RangeSource {
    override suspend fun id(): SourceId = inner.id()

    override suspend fun readTail(n: Int): ByteView = inner.readTail(n)

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> {
        val plan = RangePlanner.plan(ranges, policy)
        val fetched: List<ByteView> = when {
            plan.requests.isEmpty() -> emptyList()
            plan.requests.size == 1 || policy.parallelism == 1 -> inner.readRanges(plan.requests)
            else -> coroutineScope {
                val permits = Semaphore(policy.parallelism)
                plan.requests.map { r -> async { permits.withPermit { inner.readRanges(listOf(r)).single() } } }.awaitAll()
            }
        }
        return plan.slices.map { s -> if (s == null) ByteView.EMPTY else fetched[s.request].slice(s.offset, s.length) }
    }

    override fun close() = inner.close()
}

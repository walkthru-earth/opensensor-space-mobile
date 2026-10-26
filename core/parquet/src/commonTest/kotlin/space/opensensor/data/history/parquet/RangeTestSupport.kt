package space.opensensor.data.history.parquet

/** Counts tail reads, [RangeSource.readRanges] calls, ranges passed to [inner] and the most calls in flight at once (single-threaded test dispatchers only). */
class CountingRangeSource(private val inner: RangeSource) : RangeSource {
    var tailReads = 0
        private set
    var rangeCalls = 0
        private set
    var requests = 0
        private set
    var maxConcurrent = 0
        private set
    private var inFlight = 0

    override suspend fun id(): SourceId = inner.id()

    override suspend fun readTail(n: Int): ByteView {
        tailReads++
        return inner.readTail(n)
    }

    override suspend fun readRanges(ranges: List<LongRange>): List<ByteView> {
        rangeCalls++
        requests += ranges.size
        inFlight++
        maxConcurrent = maxOf(maxConcurrent, inFlight)
        try {
            return inner.readRanges(ranges)
        } finally {
            inFlight--
        }
    }

    override fun close() = inner.close()
}

/** A new file in the platform's temporary directory holding [bytes], removed when the test process exits where the platform allows. */
expect fun tempLocalFile(name: String, bytes: ByteArray): LocalFile

/** Replaces the whole content of [file] with [bytes]. */
expect fun writeLocalFile(file: LocalFile, bytes: ByteArray)

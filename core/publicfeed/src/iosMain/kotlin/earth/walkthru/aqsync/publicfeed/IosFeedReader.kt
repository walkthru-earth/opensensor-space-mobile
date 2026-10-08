package earth.walkthru.aqsync.publicfeed

/**
 * iOS has no Parquet reader yet. `:core:parquet` is JVM only until W8 makes it portable, so this
 * stub reports itself unavailable and the iOS map shows its empty state. Replace it with the
 * common reader when W8 lands. Nothing else in the feed changes.
 */
internal object UnavailableFeedReader : FeedReader {
    override val available: Boolean get() = false

    override fun readCells(bytes: ByteArray, source: String, ranges: List<LongRange>?): CellsTable = CellsTable.EMPTY
}

actual fun platformFeedReader(): FeedReader = UnavailableFeedReader

package earth.walkthru.aqsync.data.history.parquet

/**
 * A file on this device that [ParquetFile.open] reads: `java.io.File` on the JVM (so Android callers keep
 * passing their `File`), a path on iOS.
 */
expect class LocalFile

/** Opens the file for positional reads, no mapping. The reader holds one descriptor until it is closed. */
internal expect fun LocalFile.openReader(): ByteReader

/** The file name, used as the reader's `source` in messages. */
internal expect val LocalFile.displayName: String

/**
 * A [RangeSource] over a local file with positional reads (`FileChannel.read(dst, position)` on the
 * JVM, `pread` on iOS), no mapping. Each range is one read into a new buffer of exactly its length;
 * wrap it in a [CoalescingRangeSource] to merge neighbours. The [SourceId] version is size plus
 * modification time. Safe for concurrent reads. Reads run on `Dispatchers.IO`.
 */
expect class LocalRangeSource(file: LocalFile) : RangeSource

package earth.walkthru.aqsync.data.history.parquet

/**
 * A file on this device that [ParquetFile.open] maps: `java.io.File` on the JVM (so Android callers keep
 * passing their `File`), a path on iOS.
 */
expect class LocalFile

/** Maps the file read-only. The mapping stays valid after any handle used to create it is closed. */
internal expect fun LocalFile.mapReadOnly(): ByteReader

/** The file name, used as the reader's `source` in messages. */
internal expect val LocalFile.displayName: String

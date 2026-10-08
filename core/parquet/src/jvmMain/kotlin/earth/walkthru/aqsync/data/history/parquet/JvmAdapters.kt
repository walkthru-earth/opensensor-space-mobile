package earth.walkthru.aqsync.data.history.parquet

import java.io.OutputStream
import java.nio.ByteBuffer

/** A [ParquetSink] that writes to [out] and flushes it when the writer finishes. */
class OutputStreamSink(private val out: OutputStream) : ParquetSink {
    override fun write(bytes: ByteArray) = out.write(bytes)
    override fun flush() = out.flush()
}

/**
 * The JVM constructor the app has always called: a [ParquetWriter] over an `OutputStream`. Named like
 * the class so `ParquetWriter(out, columns, …)` and its import keep compiling unchanged.
 */
@Suppress("FunctionName")
fun ParquetWriter(
    out: OutputStream,
    columns: List<ParquetWriter.Column>,
    codec: ParquetCodec = ParquetCodec.LZ4_RAW,
    keyValueMetadata: Map<String, String> = emptyMap(),
    createdBy: String = "AQ Sync Android derived-v1",
    sortedBy: Int? = null,
): ParquetWriter = ParquetWriter(OutputStreamSink(out), columns, codec, keyValueMetadata, createdBy, sortedBy)

/** A read-only view of value [i] (no copy), or null: the `ByteBuffer` form of [ColumnValues.Binary.bytes]. */
fun ColumnValues.Binary.slice(i: Int): ByteBuffer? = if (isNull(i)) null else ByteBuffer.wrap(data, offsets[i], length(i)).slice().asReadOnlyBuffer()

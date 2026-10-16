package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.history.parquet.ParquetCodec
import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetLogicalType
import earth.walkthru.aqsync.data.history.parquet.ParquetWriter
import java.io.ByteArrayOutputStream

/** Rebuild a real telemetry fixture for a simulated producer, keeping its columns and readings. */
internal fun stationParquetFixture(bytes: ByteArray, station: String): ByteArray {
    val source = ParquetFile.wrap(bytes)
    val output = ByteArrayOutputStream()
    val writer = ParquetWriter(
        output,
        source.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) },
        ParquetCodec.UNCOMPRESSED,
        source.metadata.keyValueMetadata + ("station_id" to station),
    )
    writer.begin()
    repeat(source.rowGroupCount) { group -> writer.rowGroup(source.leaves.map { source.readColumn(it, group) }) }
    writer.finish()
    return output.toByteArray()
}

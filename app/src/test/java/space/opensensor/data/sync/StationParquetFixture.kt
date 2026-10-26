package space.opensensor.data.sync

import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.opensensorFooterJson
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetWriter
import java.io.ByteArrayOutputStream

/** Rebuild a real telemetry fixture for a simulated producer, keeping its columns and readings. [station] goes in the `opensensor` footer. */
internal fun stationParquetFixture(bytes: ByteArray, station: String): ByteArray {
    val source = ParquetFile.wrap(bytes)
    val kv = source.metadata.keyValueMetadata
    val own = OpenSensorFooter.of(kv)
    val footer = opensensorFooterJson(own?.dictionarySha256, own?.templateSha256, own?.sampleIntervalMs, station = station, purpose = own?.purpose ?: "telemetry")
    val output = ByteArrayOutputStream()
    val writer = ParquetWriter(
        output,
        source.leaves.map { ParquetWriter.Column(it.name, it.type, timestampUtc = it.timestampUtcUnit, fieldId = it.fieldId) },
        ParquetCodec.UNCOMPRESSED,
        kv + (OpenSensorFooter.KEY to footer),
    )
    writer.begin()
    repeat(source.rowGroupCount) { group -> writer.rowGroup(source.leaves.map { source.readColumn(it, group) }) }
    writer.finish()
    return output.toByteArray()
}

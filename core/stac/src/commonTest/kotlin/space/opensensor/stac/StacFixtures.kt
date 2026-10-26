package space.opensensor.stac

import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.platformH3Grid

// Device files shaped like the current CoreS3 and Waveshare files, built in memory. Only the columns
// the Item reads are written, plus the gate columns of the measurements.

const val THING = "3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13"

/** 2026-10-24T13:00:00Z, the hour of every fixture. */
const val HOUR_S = 1_792_846_800L

const val FILE_NAME = "data_1300_0f3a9c2e5b7d41e8a6c3f0b2d9e4a713_2881-3240-0.parquet"

val coreS3Template: BoardTemplate = BundledCatalog.templates.single { it.id.startsWith("cores3-") }
val waveshareTemplate: BoardTemplate = BundledCatalog.templates.single { it.id.startsWith("waveshare-") }

val h3: H3Grid = platformH3Grid()

/** The res 8 cell of Osu, Accra, the stac-opensensor example. */
val OSU_CELL: Long get() = h3.latLngToCell(5.6018, -0.1881, 8)

fun footer(mobility: OpenSensorFooter.Mobility?, thing: String? = THING) =
    OpenSensorFooter(sampleIntervalMs = 10_000, thingId = thing, mobility = mobility, purpose = "telemetry")

/** One fixture row. Null is a missing cell. */
data class Row(
    val utcUs: Long?,
    val cell: Long?,
    val pmsStatus: Int? = 4,
    val pm25: Int? = 12,
    val temperature: Float? = 28.5f,
)

/** Rows 10 s apart from [HOUR_S], each in [cell]. */
fun rowsIn(cell: Long?, count: Int = 6): List<Row> = List(count) { Row((HOUR_S + 10L * it) * 1_000_000L, cell) }

/** A device file of [rows]. Without [withCells] the file has no `h3_cell_id` column at all. */
fun deviceFile(rows: List<Row>, withCells: Boolean = true): ByteArray {
    val out = MemorySink()
    val columns = buildList {
        add(ParquetWriter.Column(UtcColumns.EVENT_US, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS))
        add(ParquetWriter.Column("pms_status", ParquetType.INT32))
        add(ParquetWriter.Column("pm25_atmospheric_ug_m3", ParquetType.INT32))
        add(ParquetWriter.Column("ambient_temperature_c", ParquetType.FLOAT))
        if (withCells) add(ParquetWriter.Column(FileItems.H3_CELL_COLUMN, ParquetType.INT64))
    }
    val w = ParquetWriter(out, columns, ParquetCodec.UNCOMPRESSED)
    w.begin()
    w.rowGroup(
        buildList {
            add(longs(rows.map { it.utcUs }))
            add(ints(rows.map { it.pmsStatus }))
            add(ints(rows.map { it.pm25 }))
            add(ColumnValues.Float32(FloatArray(rows.size) { rows[it].temperature ?: 0f }, BooleanArray(rows.size) { rows[it].temperature != null }))
            if (withCells) add(longs(rows.map { it.cell }))
        },
    )
    w.finish()
    return out.toByteArray()
}

private fun longs(values: List<Long?>) =
    ColumnValues.Int64(LongArray(values.size) { values[it] ?: 0L }, BooleanArray(values.size) { values[it] != null })

private fun ints(values: List<Int?>) =
    ColumnValues.Int32(IntArray(values.size) { values[it] ?: 0 }, BooleanArray(values.size) { values[it] != null })

/** A [ParquetSink] that collects everything in memory. */
class MemorySink : ParquetSink {
    private val chunks = ArrayList<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }
}

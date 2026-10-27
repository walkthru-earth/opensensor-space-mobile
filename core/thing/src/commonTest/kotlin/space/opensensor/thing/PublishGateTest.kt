package space.opensensor.thing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.UtcColumns
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetSink
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.model.BundledCatalog
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.platformH3Grid
import space.opensensor.stac.FileItemResult
import space.opensensor.stac.FileItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PublishGateTest {
    private val thing = "3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13"
    private val kinds = ThingKinds(listOf(OpenSensorBoardKind, healthKind, feedKind, localOnlyKind))
    private val optedIn = UploadConsent { it == thing }

    @Test
    fun nothingIsPublishedByDefault() {
        val gate = PublishGate(kinds)
        for (kind in kinds.kinds) assertNull(gate.ticket(kind.id, thing), "${kind.id} without an opt in")
    }

    @Test
    fun healthFeedAndNeverKindsGetNoTicketEvenOptedIn() {
        val gate = PublishGate(kinds, optedIn)
        assertNull(gate.ticket(healthKind.id, thing))
        assertNull(gate.ticket(feedKind.id, thing))
        assertNull(gate.ticket(localOnlyKind.id, thing))
        assertNull(gate.ticket(KindId("phone"), thing), "a kind this app does not list")
    }

    @Test
    fun anOptedInBoardGetsATicketForItsThingOnly() {
        val gate = PublishGate(kinds, optedIn)
        val ticket = assertNotNull(gate.ticket(OpenSensorBoardKind.id, thing))
        assertEquals(OpenSensorBoardKind.PUBLISHED_MAX_RES, ticket.policy.maxRes)
        assertNull(gate.ticket(OpenSensorBoardKind.id, "0b7e0f53-8f5e-4c3a-9d55-1a2b3c4d5e6f"))
    }

    @Test
    fun theItemNamesNoCellFinerThanThePolicy() {
        val h3 = platformH3Grid()
        val cell = h3.latLngToCell(5.6018, -0.1881, 8)
        val ticket = assertNotNull(PublishGate(kinds, optedIn).ticket(OpenSensorBoardKind.id, thing))
        val result = PublishGate(kinds, optedIn).item(ticket, FILE_NAME, ByteArrayReader(deviceFile(cell)), footer(thing), template, h3)
        val item = Json.parseToJsonElement(assertIs<FileItemResult.Item>(result).json).jsonObject
        val code = item["properties"]!!.jsonObject["grid:code"]!!.jsonPrimitive.content
        assertEquals("H3-${H3Index.toHex(H3Index.parent(cell, OpenSensorBoardKind.PUBLISHED_MAX_RES))}", code)
    }

    @Test
    fun optingOutAfterTheTicketBuildsNoItem() {
        var granted = true
        val gate = PublishGate(kinds, UploadConsent { granted && it == thing })
        val ticket = assertNotNull(gate.ticket(OpenSensorBoardKind.id, thing))
        granted = false
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        assertNull(gate.item(ticket, FILE_NAME, ByteArrayReader(deviceFile(cell)), footer(thing), template))
        assertNull(gate.ticket(OpenSensorBoardKind.id, thing))
    }

    @Test
    fun aFileOfAnotherThingGetsNoItem() {
        val ticket = assertNotNull(PublishGate(kinds, optedIn).ticket(OpenSensorBoardKind.id, thing))
        val other = "0b7e0f53-8f5e-4c3a-9d55-1a2b3c4d5e6f"
        val result = PublishGate(kinds, optedIn).item(ticket, FILE_NAME, ByteArrayReader(deviceFile(0L)), footer(other), template)
        assertEquals(FileItemResult.NoThingId, result)
    }

    private val template = checkNotNull(BundledCatalog.template("3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"))

    private fun footer(thing: String) = OpenSensorFooter(sampleIntervalMs = 10_000, thingId = thing, mobility = OpenSensorFooter.Mobility.FIXED, purpose = "telemetry")

    /** Six rows 10 s apart from 2026-10-24T13:00:00Z in [cell]. */
    private fun deviceFile(cell: Long): ByteArray {
        val rows = 6
        val out = MemorySink()
        val columns = listOf(
            ParquetWriter.Column(UtcColumns.EVENT_US, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.MICROS),
            ParquetWriter.Column(FileItems.H3_CELL_COLUMN, ParquetType.INT64),
        )
        val w = ParquetWriter(out, columns, ParquetCodec.UNCOMPRESSED)
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(rows) { (HOUR_S + 10L * it) * 1_000_000L }, BooleanArray(rows) { true }),
                ColumnValues.Int64(LongArray(rows) { cell }, BooleanArray(rows) { true }),
            ),
        )
        w.finish()
        return out.toByteArray()
    }

    private class MemorySink : ParquetSink {
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

    private companion object {
        const val FILE_NAME = "data_1300_0f3a9c2e5b7d41e8a6c3f0b2d9e4a713_2881-3240-0.parquet"
        const val HOUR_S = 1_792_846_800L
    }
}

package space.opensensor.thing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import space.opensensor.model.ThingId
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.platformH3Grid
import space.opensensor.stac.FileItemResult
import space.opensensor.stac.FileItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublishGateTest {
    private val thing = "3f2b9c1e-6a7d-4e58-9b1c-2d4f6a8e0c13"
    private val phoneKind = FakeKind("phone-log", DataSource.PhoneLog(PublishPolicy.OptInCcBy(publishedRes = 8, trimSessionEnds = true)))
    private val kinds = ThingKinds(listOf(OpenSensorBoardKind, healthKind, feedKind, localOnlyKind, phoneKind))
    private val optedIn = UploadConsent { it == thing }

    @Test
    fun nothingIsPublishedByDefault() {
        val gate = PublishGate(kinds)
        for (kind in kinds.kinds) assertNull(gate.ticket(kind.id, thing), "${kind.id} without an opt in")
        assertNull(gate.ticket(phoneKind.id, PHONE.uuid), "the phone's own Thing without an opt in")
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
        assertEquals(8, ticket.policy.publishedRes)
        assertEquals(OpenSensorBoardKind.PUBLISHED_RES, ticket.policy.publishedRes)
        assertNull(gate.ticket(OpenSensorBoardKind.id, "0b7e0f53-8f5e-4c3a-9d55-1a2b3c4d5e6f"))
    }

    @Test
    fun aBoardFileIsClearedWithTheBoardsOwnSidecarText() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        val bytes = deviceFile(cell)
        val boardItem = boardItem(bytes, cell)
        // The board prints its Item its own way. The phone carries those bytes, not the text it rebuilt.
        val sidecar = PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(boardItem.json))
        val publication = cleared(boardGate().boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, sidecar))
        assertEquals(sidecar, publication.itemJson)
        assertNotEquals(boardItem.json, publication.itemJson)
        assertEquals(ItemWriter.BOARD, publication.writer)
        assertEquals(boardItem.sidecarPath, publication.sidecarPath)
        assertEquals(thing, publication.thing)
        val item = Json.parseToJsonElement(publication.itemJson).jsonObject
        assertEquals("H3-${H3Index.toHex(cell)}", item["properties"]!!.jsonObject["grid:code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aBoardSidecarThatDiffersIsNotCleared() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        val bytes = deviceFile(cell)
        val board = Json.parseToJsonElement(boardItem(bytes, cell).json).jsonObject
        val moved = JsonObject(board + ("collection" to JsonPrimitive("0b7e0f53-8f5e-4c3a-9d55-1a2b3c4d5e6f")))
        val gate = boardGate()
        assertEquals(PublishCheck.SidecarDiffers("$.collection"), gate.boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, moved.toString()))
        assertEquals(PublishCheck.SidecarUnreadable, gate.boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, "[1]"))
        assertEquals(PublishCheck.SidecarUnreadable, gate.boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, "{"))
        assertEquals(PublishCheck.NoSidecar, gate.boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, null))
    }

    @Test
    fun aFileFinerThanResolution8IsNotPublished() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 10)
        assertEquals(PublishCheck.NoItem(FileItemResult.WrongResolution(10)), boardCheck(cell))
    }

    @Test
    fun aFileCoarserThanResolution8IsNotPublished() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 7)
        assertEquals(PublishCheck.NoItem(FileItemResult.WrongResolution(7)), boardCheck(cell))
    }

    /** What the gate says about a fixed board file whose rows sit in [cell], with the board's own sidecar. */
    private fun boardCheck(cell: Long): PublishCheck {
        val bytes = deviceFile(cell)
        val sidecar = (FileItems.build(FILE_NAME, ByteArrayReader(bytes), footer(thing), template, 8) as? FileItemResult.Item)?.json
        return boardGate().boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(thing), template, sidecar)
    }

    @Test
    fun noItemThePhoneBuildsForABoardFileReachesTheUpload() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        val bytes = deviceFile(cell)
        // A gate that knows this phone, with the board and the phone both opted in.
        val gate = PublishGate(kinds, UploadConsent { it == thing || it == PHONE.uuid }, PHONE)
        val boardTicket = assertNotNull(gate.ticket(OpenSensorBoardKind.id, thing))
        // The phone path refuses a board ticket, and a phone ticket for a file whose footer names a board.
        assertEquals(PublishCheck.NotThePhone, gate.phoneFile(boardTicket, FILE_NAME, ByteArrayReader(bytes), footer(thing), template))
        val phoneTicket = assertNotNull(gate.ticket(phoneKind.id, PHONE.uuid))
        assertEquals(PublishCheck.NotThePhone, gate.phoneFile(phoneTicket, FILE_NAME, ByteArrayReader(bytes), footer(thing), template))
        // A gate without a phone Thing, the default, clears nothing on the phone path.
        val plain = PublishGate(kinds, UploadConsent { true })
        val plainTicket = assertNotNull(plain.ticket(phoneKind.id, PHONE.uuid))
        assertEquals(PublishCheck.NotThePhone, plain.phoneFile(plainTicket, FILE_NAME, ByteArrayReader(bytes), footer(PHONE.uuid), template))
        // The board path refuses a phone ticket, and without the board's sidecar it clears nothing.
        assertEquals(PublishCheck.NotABoard, gate.boardFile(phoneTicket, FILE_NAME, ByteArrayReader(bytes), footer(PHONE.uuid), template, null))
        assertEquals(PublishCheck.NoSidecar, gate.boardFile(boardTicket, FILE_NAME, ByteArrayReader(bytes), footer(thing), template, null))
    }

    @Test
    fun thePhonesOwnFileIsClearedWithTheItemThePhoneBuilt() {
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        val gate = PublishGate(kinds, UploadConsent { it == PHONE.uuid }, PHONE)
        val ticket = assertNotNull(gate.ticket(phoneKind.id, PHONE.uuid))
        val publication = cleared(gate.phoneFile(ticket, FILE_NAME, ByteArrayReader(deviceFile(cell)), footer(PHONE.uuid), template))
        assertEquals(ItemWriter.PHONE, publication.writer)
        assertEquals(PHONE.uuid, Json.parseToJsonElement(publication.itemJson).jsonObject["collection"]!!.jsonPrimitive.content)
        assertTrue(publication.sidecarPath.startsWith("thing=${PHONE.uuid}/"))
    }

    @Test
    fun optingOutAfterTheTicketClearsNothing() {
        var granted = true
        val gate = PublishGate(kinds, UploadConsent { granted && (it == thing || it == PHONE.uuid) }, PHONE)
        val ticket = assertNotNull(gate.ticket(OpenSensorBoardKind.id, thing))
        val phoneTicket = assertNotNull(gate.ticket(phoneKind.id, PHONE.uuid))
        granted = false
        val cell = platformH3Grid().latLngToCell(5.6018, -0.1881, 8)
        val bytes = deviceFile(cell)
        val sidecar = boardItem(bytes, cell).json
        assertEquals(PublishCheck.OptedOut, gate.boardFile(ticket, FILE_NAME, ByteArrayReader(bytes), footer(thing), template, sidecar))
        assertEquals(PublishCheck.OptedOut, gate.phoneFile(phoneTicket, FILE_NAME, ByteArrayReader(bytes), footer(PHONE.uuid), template))
        assertNull(gate.ticket(OpenSensorBoardKind.id, thing))
    }

    @Test
    fun aFileOfAnotherThingIsNotCleared() {
        val other = "0b7e0f53-8f5e-4c3a-9d55-1a2b3c4d5e6f"
        val bytes = deviceFile(0L)
        assertEquals(PublishCheck.OtherThing, boardGate().boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), footer(other), template, "{}"))
        assertEquals(PublishCheck.NoItem(FileItemResult.NoThingId), boardGate().boardFile(boardTicket(), FILE_NAME, ByteArrayReader(bytes), null, template, "{}"))
    }

    private fun boardGate() = PublishGate(kinds, optedIn)

    private fun boardTicket() = assertNotNull(boardGate().ticket(OpenSensorBoardKind.id, thing))

    /** The Item the board writes for its file [bytes], all rows in [cell]. */
    private fun boardItem(bytes: ByteArray, cell: Long): FileItemResult.Item =
        assertIs<FileItemResult.Item>(FileItems.build(FILE_NAME, ByteArrayReader(bytes), footer(thing), template, 8), "cell ${H3Index.toHex(cell)}")

    private fun cleared(check: PublishCheck): Publication = assertIs<PublishCheck.Cleared>(check, "cleared, not $check").publication

    private val template = checkNotNull(BundledCatalog.template("3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9"))

    private fun footer(thing: String) = OpenSensorFooter(sampleIntervalMs = 10_000, thingId = thing, mobility = OpenSensorFooter.Mobility.FIXED, purpose = "telemetry", rotationIntervalS = 900)

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
        val PHONE = ThingId.PhoneInstall("5d1c7a2e-9b40-4f63-8e15-0a6b3c9d2f71")
        val PRETTY = Json { prettyPrint = true }
    }
}

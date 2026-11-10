package space.opensensor.data.history

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.ColumnValues
import space.opensensor.data.history.parquet.ParquetCodec
import space.opensensor.data.history.parquet.ParquetFile
import space.opensensor.data.history.parquet.ParquetLogicalType
import space.opensensor.data.history.parquet.ParquetType
import space.opensensor.data.history.parquet.ParquetWriter
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.model.BoardTemplate
import space.opensensor.net.BodyLimit
import space.opensensor.net.HttpRangeEngine
import space.opensensor.net.HttpRangeException
import space.opensensor.net.HttpRangeResponse
import space.opensensor.net.HttpTransportException
import space.opensensor.net.MemoryBlockStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * #260. A file footer whose desc nothing resolves gets its template from the footer href, once, only on
 * the firmware template host, only under the network switch, and only when the bytes hash to the desc.
 * Runs on the JVM and the iOS simulator.
 */
class TemplateHrefsTest {
    private val thing = "thing-a"
    private val dict = "5011" + "ab".repeat(30)
    private val json = soilTemplateJson(dict)
    private val bytes = json.encodeToByteArray()
    private val desc = ParquetVerify.sha256Hex(bytes)
    private val commit = "bb618f32a8a9bfe6a633a22ad5e99d319d55d46b"
    private val href = "https://raw.githubusercontent.com/walkthru-earth/opensensor-space-firmware/$commit/docs/shared/templates/soil-probe/$dict.json"
    private val link = TemplateLink(desc, dict, href)

    /** Answers every GET with [answer] and keeps each URL and limit it was asked for. */
    private class FakeEngine(var answer: suspend (String) -> HttpRangeResponse) : HttpRangeEngine {
        val urls = ArrayList<String>()
        val limits = ArrayList<BodyLimit>()

        override suspend fun get(url: String, range: String?, ifRange: String?, ifNoneMatch: String?, limit: BodyLimit): HttpRangeResponse {
            urls += url
            limits += limit
            val response = answer(url)
            // The engine contract, a body over the limit fails instead of returning.
            if (response.body.size > limit.of(response.status)) throw HttpRangeException(response.status, url)
            return response
        }
    }

    private class Harness(scope: TestScope, answer: suspend (String) -> HttpRangeResponse) {
        val io = StandardTestDispatcher(scope.testScheduler)
        val store = MemoryBlockStore()
        val served = ServedTemplates()
        val descriptors = BoardDescriptors(store, served, bundled = emptyList(), io = io)
        val switch = MutableStateFlow(true)
        val engine = FakeEngine(answer)
        val logs = ArrayList<String>()
        val hrefs = TemplateHrefs(engine, descriptors, switch, log = { logs += it })
        val templates = FileTemplates.over(served)
    }

    private fun TestScope.harness(answer: suspend (String) -> HttpRangeResponse = { response(200, bytes) }) = Harness(this, answer)

    private fun response(status: Int, body: ByteArray = ByteArray(0)) = HttpRangeResponse(status, null, null, null, body)

    /** No log line carries the template body or the href, only the desc and a reason. */
    private fun Harness.assertQuietLogs() {
        assertTrue(logs.none { "OpenSensorTemplate" in it || "soil_temperature_c" in it || "https://" in it }, logs.toString())
    }

    @Test
    fun aGoodShaBindsTheTemplateAndCachesItByDesc() = runTest {
        val h = harness()
        val fetched = assertIs<HrefOutcome.Fetched>(h.hrefs.fetch(link))
        assertEquals(desc, fetched.template.key.desc)
        assertEquals(listOf(href), h.engine.urls)
        assertEquals(TemplateHrefs.MAX_BYTES, h.engine.limits.single().whole)
        assertEquals(fetched.template, h.templates.template(dict, desc))
        assertEquals(listOf(fetched.template), h.templates.changes.value)
        assertEquals(listOf("$desc.json"), h.store.list().map { it.name })
        assertEquals(listOf("template $desc fetched from its href"), h.logs)
        // Held now, so a second file of that desc sends nothing.
        assertEquals(HrefOutcome.Known, h.hrefs.fetch(link))
        assertEquals(1, h.engine.urls.size)
    }

    @Test
    fun aBadShaNeverBindsAndLogsOnlyTheDesc() = runTest {
        val tampered = soilTemplateJson(dict).replace("\"mean\"", "\"max\"").encodeToByteArray()
        val h = harness { response(200, tampered) }
        assertIs<HrefOutcome.Rejected>(h.hrefs.fetch(link))
        assertNull(h.templates.template(dict, desc))
        assertTrue(h.served.all.value.isEmpty())
        assertTrue(h.store.list().isEmpty())
        assertTrue(h.logs.single().startsWith("template $desc rejected"))
        h.assertQuietLogs()
        // Remembered for this run.
        assertEquals(HrefOutcome.Tried, h.hrefs.fetch(link))
        assertEquals(1, h.engine.urls.size)
    }

    @Test
    fun aNotFoundFailsAndIsNotRetried() = runTest {
        val h = harness { response(404) }
        assertEquals(HrefOutcome.Failed("HTTP 404"), h.hrefs.fetch(link))
        assertEquals(HrefOutcome.Tried, h.hrefs.fetch(link))
        assertEquals(1, h.engine.urls.size)
        assertTrue(h.served.all.value.isEmpty())
        h.assertQuietLogs()
    }

    @Test
    fun aRedirectFailsWithoutFollowingIt() = runTest {
        val h = harness { response(302) }
        assertEquals(HrefOutcome.Failed("redirect"), h.hrefs.fetch(link))
        assertEquals(listOf(href), h.engine.urls)
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun anOversizeBodyFails() = runTest {
        val h = harness { response(200, ByteArray(TemplateHrefs.MAX_BYTES.toInt() + 1)) }
        assertEquals(HrefOutcome.Failed("body over ${TemplateHrefs.MAX_BYTES} bytes"), h.hrefs.fetch(link))
        assertTrue(h.served.all.value.isEmpty())
        assertTrue(h.store.list().isEmpty())
    }

    @Test
    fun noNetworkFailsQuietly() = runTest {
        val h = harness { throw HttpTransportException("offline") }
        assertEquals(HrefOutcome.Failed("no network"), h.hrefs.fetch(link))
        h.assertQuietLogs()
    }

    @Test
    fun aStalledRequestTimesOut() = runTest {
        val h = harness {
            delay(60.seconds)
            response(200, bytes)
        }
        assertEquals(HrefOutcome.Failed("timeout"), h.hrefs.fetch(link))
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun aNonHttpsHrefIsRefusedWithoutARequest() = runTest {
        val h = harness()
        assertEquals(HrefOutcome.Refused, h.hrefs.fetch(link.copy(href = href.replace("https://", "http://"))))
        assertTrue(h.engine.urls.isEmpty())
    }

    @Test
    fun anotherHostOrPathIsRefusedWithoutARequest() = runTest {
        val h = harness()
        val others = listOf(
            href.replace("raw.githubusercontent.com", "example.com"),
            href.replace("raw.githubusercontent.com", "raw.githubusercontent.com.example.com"),
            href.replace("raw.githubusercontent.com", "raw.githubusercontent.com:8443"),
            href.replace("https://", "https://user@"),
            href.replace("walkthru-earth/", "someone-else/"),
            href.replace(commit, "main"),
            href.replace("/docs/shared/templates/", "/docs/shared/templates/../../"),
            "$href?x=1",
            "$href#x",
            "https://example.com/templates/soil-probe/$dict.json",
        )
        for (other in others) assertEquals(HrefOutcome.Refused, h.hrefs.fetch(link.copy(href = other)), other)
        assertTrue(h.engine.urls.isEmpty())
    }

    @Test
    fun theSwitchOffSendsNothingAndOnFetches() = runTest {
        val h = harness()
        h.switch.value = false
        assertEquals(HrefOutcome.Off, h.hrefs.fetch(link))
        assertTrue(h.engine.urls.isEmpty())
        // Off is not a try, so the desc is fetched once the switch is on.
        h.switch.value = true
        assertIs<HrefOutcome.Fetched>(h.hrefs.fetch(link))
        assertEquals(1, h.engine.urls.size)
    }

    @Test
    fun aTemplateOfAnotherDictIsRejected() = runTest {
        val h = harness()
        assertEquals(HrefOutcome.Rejected("template dict differs from the file footer"), h.hrefs.fetch(link.copy(dict = "cd".repeat(32))))
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun aCachedTemplateOnDiskIsKnownWithoutARequest() = runTest {
        val h = harness()
        h.store.write("$desc.json", bytes)
        assertEquals(HrefOutcome.Known, h.hrefs.fetch(link))
        assertTrue(h.engine.urls.isEmpty())
        assertNotNull(h.templates.template(dict, desc))
    }

    @Test
    fun theFooterHrefParsesIntoALink() {
        val footer = assertNotNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.KEY to footerJson(href))))
        assertEquals(href, footer.templateHref)
        assertEquals(link, TemplateLink.of(footer))
        // A parsed footer always has a desc and an href member. One of the wrong kind gives no link.
        val numberHref = footerJson(href).replace("\"href\":\"$href\"", "\"href\":7")
        val noHref = assertNotNull(OpenSensorFooter.of(mapOf(OpenSensorFooter.KEY to numberHref)))
        assertEquals(desc, noHref.templateSha256)
        assertNull(TemplateLink.of(noHref))
        assertNull(TemplateLink.of(null))
    }

    // The end to end path. Index, request, fetch, resolve.

    private fun footerJson(templateHref: String) =
        opensensorFooterJson(dict, desc, 60_000, board = "soil-probe", schema = "soil-probe-v1", thing = thing, templateHref = templateHref)

    /** Five soil rows a minute apart, written under the soil template that [href] serves. */
    private fun soilFile(): ByteArray {
        val out = MemorySink()
        val w = ParquetWriter(
            out,
            listOf(
                ParquetWriter.Column(UtcColumns.EVENT_NS, ParquetType.INT64, timestampUtc = ParquetLogicalType.TimeUnit.NANOS),
                ParquetWriter.Column("soil_temperature_c", ParquetType.FLOAT),
                ParquetWriter.Column("soil_moisture_percent", ParquetType.FLOAT),
            ),
            ParquetCodec.UNCOMPRESSED,
            keyValueMetadata = mapOf(OpenSensorFooter.KEY to footerJson(href)),
        )
        w.begin()
        w.rowGroup(
            listOf(
                ColumnValues.Int64(LongArray(5) { (HUMID_HOUR_S + it * 60L) * 1_000_000_000L }, null),
                ColumnValues.Float32(floatArrayOf(14f, 14.5f, 15f, 15.5f, 16f), null),
                ColumnValues.Float32(floatArrayOf(30f, 31f, 32f, 33f, 34f), null),
            ),
        )
        w.finish()
        return out.toByteArray()
    }

    private class Store : ArchiveStore {
        val files = HashMap<ArchiveKey, ByteArray>()
        override suspend fun partialLengths(thing: String): Map<String, Long> = emptyMap()
        override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter = error("read only")
        override suspend fun discardPartial(key: ArchiveKey) = Unit
        override suspend fun exists(key: ArchiveKey): Boolean = key in files
        override fun reader(key: ArchiveKey, partial: Boolean): ByteReader = ByteArrayReader(files.getValue(key))
        override suspend fun commit(key: ArchiveKey) = Unit
        override suspend fun listFinal(thing: String?): List<ArchiveKey> = files.keys.filter { thing == null || it.thing == thing }
    }

    private class Index : ArchiveIndex {
        val rows = MutableStateFlow<List<ArchiveRecord>>(emptyList())
        override fun observeAll(): Flow<List<ArchiveRecord>> = rows
        override fun observeThing(thing: String): Flow<List<ArchiveRecord>> = rows.map { list -> list.filter { it.thing == thing } }
        override suspend fun get(key: ArchiveKey): ArchiveRecord? = rows.value.firstOrNull { it.key == key }
        override suspend fun put(record: ArchiveRecord) {
            rows.value = rows.value.filterNot { it.key == record.key } + record
        }
        override suspend fun remove(key: ArchiveKey) {
            rows.value = rows.value.filterNot { it.key == key }
        }
        override suspend fun unindexed(limit: Int): List<ArchiveRecord> = rows.value.filter { !it.isIndexed }.take(limit)
        override suspend fun timeline(thing: String, fromNs: Long, toNs: Long): List<ArchiveRecord> =
            rows.value.filter { it.thing == thing && it.onTimeline(fromNs, toNs) }.sortedWith(ArchiveRecord.TIMELINE_ORDER)
        override suspend fun indexedForThing(thing: String): List<ArchiveRecord> = rows.value.filter { it.thing == thing && it.isIndexed }
        override suspend fun things(): List<String> = rows.value.map { it.thing }.distinct()
    }

    /** The buckets of [file] through the app's resolver over [templates], for the soil metrics. */
    private fun buckets(file: ByteArray, templates: FileTemplates): List<Bucket> {
        val metrics = Metrics.of(BoardTemplate.parse(json, desc = desc))
        return Aggregator(thing, metrics = metrics, templates = templates).apply { add(ParquetFile.wrap(file)) }.buckets()
    }

    private suspend fun Harness.archive(file: ByteArray): Pair<Store, Index> {
        val store = Store()
        val index = Index()
        val name = "thing=$thing/year=2026/month=09/day=17/data_1000_a_2-1-1.parquet"
        val record = ArchiveRecord(thing, name, file.size.toLong(), crc32 = 1, sha256 = ParquetVerify.sha256Hex(file), syncedAtEpochMs = 1, sourceDevice = "ble")
        store.files[record.key] = file
        index.put(record)
        return store to index
    }

    @Test
    fun anUnbundledTemplateResolvesTheFileAfterOneFetch() = runTest {
        val h = harness()
        val file = soilFile()
        val (store, index) = h.archive(file)
        val repair = ArchiveIndexRepair(store, index, io = h.io, nowEpochMs = { 42L }, templates = h.templates, onTemplateLink = h.hrefs::request)

        // Not bundled and not served. The file keeps its rows and has no metric values.
        val before = buckets(file, h.templates)
        assertTrue(before.isNotEmpty())
        assertTrue(before.all { it.rows > 0 && it.metrics.isEmpty() })

        // Indexing the file asks for its template, and the History start fetches it once.
        repair.backfillAll()
        assertEquals(desc, index.rows.value.single().templateSha256)
        assertEquals(listOf(link), repair.unresolvedTemplateLinks())
        val running = launch { h.hrefs.run(repair::unresolvedTemplateLinks) }
        advanceUntilIdle()
        running.cancel()

        assertEquals(listOf(href), h.engine.urls)
        assertEquals(desc, h.templates.template(dict, desc)?.key?.desc)
        assertEquals(desc, h.templates.changes.value.single().key.desc)
        assertTrue(repair.unresolvedTemplateLinks().isEmpty())
        val after = buckets(file, h.templates)
        assertEquals(before.map { it.rows }, after.map { it.rows })
        val hour = after.single { it.resolution == Resolution.HOUR }
        assertEquals(5, hour.metrics.getValue("soil_temperature_c").n)
        assertEquals(15f, hour.metrics.getValue("soil_temperature_c").mean, 1e-4f)
        assertEquals(32f, hour.metrics.getValue("soil_moisture_percent").mean, 1e-4f)
    }

    @Test
    fun theSweepWaitsForTheSwitchAndThenFetches() = runTest {
        val h = harness()
        val (store, index) = h.archive(soilFile())
        // Indexed earlier with no hook, as on a phone that synced before this app version.
        val repair = ArchiveIndexRepair(store, index, io = h.io, nowEpochMs = { 42L }, templates = h.templates)
        repair.backfillAll()
        h.switch.value = false
        val running = launch { h.hrefs.run(repair::unresolvedTemplateLinks) }
        advanceUntilIdle()
        assertTrue(h.engine.urls.isEmpty())

        h.switch.value = true
        advanceUntilIdle()
        running.cancel()
        assertEquals(listOf(href), h.engine.urls)
        assertNotNull(h.templates.template(dict, desc))
    }

    @Test
    fun aResolvedFileRequestsNothing() = runTest {
        val h = harness()
        h.descriptors.adopt(desc, dict, bytes)
        val (store, index) = h.archive(soilFile())
        val requested = ArrayList<TemplateLink>()
        ArchiveIndexRepair(store, index, io = h.io, nowEpochMs = { 42L }, templates = h.templates, onTemplateLink = { requested += it }).backfillAll()
        assertTrue(requested.isEmpty())
    }

    private fun soilTemplateJson(dict: String) = """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "soil-probe-v1",
  "instruments": ["probe"],
  "opensensor:sample_interval_s": 60,
  "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"],
    "table:primary_datetime": "event_time_utc_ns", "table:columns": [
      {"name": "event_time_utc_ns", "type": "int64", "roles": ["referenceInformation"]},
      {"name": "soil_temperature_c", "type": "float", "unit": "Cel", "roles": ["physicalMeasurement"], "opensensor:aggregate": "mean"},
      {"name": "soil_moisture_percent", "type": "float", "unit": "%", "roles": ["physicalMeasurement"], "opensensor:aggregate": "mean"}]}},
  "links": [],
  "firmware": {"schema": "soil-probe-v1", "proto_min": 3, "dict": "$dict",
    "live_keys": {"event_time_utc_ns": "utc", "soil_temperature_c": "st", "soil_moisture_percent": "sm"}}
}
"""
}

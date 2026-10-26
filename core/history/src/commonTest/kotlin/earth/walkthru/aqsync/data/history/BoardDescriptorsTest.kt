package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.ble.NoDescriptorException
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.data.protocol.sha256Hex
import earth.walkthru.aqsync.net.MemoryBlockStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import space.opensensor.model.BoardTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardDescriptorsTest {
    private val dict = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"
    private val bytes = template(dict).encodeToByteArray()
    private val sha = ParquetVerify.sha256Hex(bytes)
    private val info = DeviceInfo(proto = 3, dict = dict, desc = sha.take(16), station = "s")

    private class Harness(scope: TestScope) {
        val store = MemoryBlockStore()
        val served = ServedTemplates()
        val descriptors = BoardDescriptors(store, served, bundled = emptyList(), io = StandardTestDispatcher(scope.testScheduler))
        var downloads = 0
    }

    private fun TestScope.harness() = Harness(this)

    @Test
    fun verifiedTemplateIsCachedAndServed() = runTest {
        val h = harness()
        val outcome = h.descriptors.ensure(info) { h.downloads++; bytes }
        val fetched = assertIs<DescriptorOutcome.Fetched>(outcome)
        assertEquals(sha, fetched.template.key.desc)
        assertEquals(listOf("$sha.json"), h.store.list().map { it.name })
        assertEquals(listOf(fetched.template), h.served.all.value)
        // The capabilities resolve the unbundled dict through the served template.
        assertNotNull(DeviceCapabilities.from(info, h.served.all.value).station)
        assertEquals(null, DeviceCapabilities.from(info, emptyList()).station)
    }

    @Test
    fun knownDescSkipsTheDownload() = runTest {
        val h = harness()
        h.descriptors.ensure(info) { h.downloads++; bytes }
        assertEquals(DescriptorOutcome.Known, h.descriptors.ensure(info) { h.downloads++; bytes })
        assertEquals(1, h.downloads)
    }

    @Test
    fun bundledTemplateWithTheDescSkipsTheDownload() = runTest {
        val bundled = BoardTemplate.parse(template(dict), desc = sha)
        val descriptors = BoardDescriptors(MemoryBlockStore(), ServedTemplates(), listOf(bundled), StandardTestDispatcher(testScheduler))
        var downloads = 0
        assertEquals(DescriptorOutcome.Known, descriptors.ensure(info) { downloads++; bytes })
        assertEquals(0, downloads)
    }

    @Test
    fun cachedTemplateLoadsAndSkipsTheDownload() = runTest {
        val first = harness()
        first.descriptors.ensure(info) { bytes }
        val again = ServedTemplates()
        val reopened = BoardDescriptors(first.store, again, emptyList(), StandardTestDispatcher(testScheduler))
        assertEquals(1, reopened.load())
        var downloads = 0
        assertEquals(DescriptorOutcome.Known, reopened.ensure(info) { downloads++; bytes })
        assertEquals(0, downloads)
    }

    @Test
    fun loadDropsFilesThatNoLongerVerify() = runTest {
        val h = harness()
        h.store.write("$sha.json", "{}".encodeToByteArray())
        h.store.write("notes.txt", bytes)
        assertEquals(0, h.descriptors.load())
        assertTrue(h.store.list().isEmpty())
    }

    @Test
    fun prefixMismatchIsRejected() = runTest {
        val h = harness()
        val other = info.copy(desc = "0123456789abcdef")
        assertIs<DescriptorOutcome.Rejected>(h.descriptors.ensure(other) { bytes })
        assertTrue(h.store.list().isEmpty())
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun wrongTypeIsRejected() = runTest {
        val h = harness()
        val notTemplate = template(dict).replace("OpenSensorTemplate", "Feature").encodeToByteArray()
        val notInfo = info.copy(desc = ParquetVerify.sha256Hex(notTemplate).take(16))
        assertIs<DescriptorOutcome.Rejected>(h.descriptors.ensure(notInfo) { notTemplate })
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun dictMismatchIsRejected() = runTest {
        val h = harness()
        val otherDict = "e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b"
        assertIs<DescriptorOutcome.Rejected>(h.descriptors.ensure(info.copy(dict = otherDict)) { bytes })
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun errorSeventeenIsNoDescriptor() = runTest {
        val h = harness()
        val outcome = h.descriptors.ensure(info) {
            throw NoDescriptorException(Frame.Error(Opcode.GET_DESCRIPTOR, ProtocolError.NO_DESCRIPTOR.code, ""))
        }
        assertEquals(DescriptorOutcome.NoDescriptor, outcome)
    }

    @Test
    fun absentOrMalformedDescNeverDownloads() = runTest {
        val h = harness()
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(desc = null)) { h.downloads++; bytes })
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(desc = "abc")) { h.downloads++; bytes })
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(dict = null)) { h.downloads++; bytes })
        assertEquals(0, h.downloads)
    }

    private fun template(dict: String) = """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "served-board-v1",
  "instruments": ["probe"],
  "opensensor:sample_interval_s": 10,
  "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"],
    "table:primary_datetime": "event_time_utc_us", "table:columns": [
      {"name": "event_time_utc_us", "type": "int64", "roles": ["referenceInformation"]}]}},
  "links": [],
  "firmware": {"schema": "served-board-v1", "proto_min": 3, "dict": "$dict", "live_keys": {"event_time_utc_us": "utc"}}
}
"""
}

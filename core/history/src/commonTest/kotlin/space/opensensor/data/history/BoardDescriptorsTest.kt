package space.opensensor.data.history

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import space.opensensor.data.ble.NoDescriptorException
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.net.BlockStore
import space.opensensor.net.MemoryBlockStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardDescriptorsTest {
    private val dict = "0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82"
    private val bytes = template(dict).encodeToByteArray()
    private val sha = ParquetVerify.sha256Hex(bytes)
    private val info = DeviceInfo(proto = 3, dict = dict, desc = sha.take(16), station = "s")

    /** A [MemoryBlockStore] whose next [failDeletes] deletes throw, as a cache directory the OS locked. */
    private class FlakyStore(private val inner: MemoryBlockStore = MemoryBlockStore()) : BlockStore by inner {
        var failDeletes = 0
        override fun delete(name: String) {
            if (failDeletes > 0) {
                failDeletes--
                throw IllegalStateException("delete failed")
            }
            inner.delete(name)
        }
    }

    private class Harness(scope: TestScope) {
        val store = FlakyStore()
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
    fun everyBundledIndexDescSkipsTheDownload() = runTest {
        val descriptors = BoardDescriptors(MemoryBlockStore(), ServedTemplates(), io = StandardTestDispatcher(testScheduler))
        assertTrue(BundledCatalog.templates.isNotEmpty())
        for (bundled in BundledCatalog.templates) {
            val desc = assertNotNull(bundled.key.desc, "index.json gives ${bundled.dict} its desc")
            val device = info.copy(dict = bundled.dict, desc = desc.take(16))
            assertEquals(DescriptorOutcome.Known, descriptors.ensure(device) { error("bundled ${bundled.dict} must not download") })
        }
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
        // A valid template under a name its bytes do not hash to, so only the re-hash rejects it.
        h.store.write("${"0".repeat(64)}.json", bytes)
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
    fun freshTemplatesStayBoundedOnDiskAndInMemory() = runTest {
        val h = harness()
        val max = BoardDescriptors.MAX_TEMPLATES
        val shas = (0 until max + 4).map { n ->
            val served = template(dict).replace("served-board-v1", "served-board-$n").encodeToByteArray()
            val hex = ParquetVerify.sha256Hex(served)
            assertIs<DescriptorOutcome.Fetched>(h.descriptors.ensure(info.copy(desc = hex.take(16))) { served })
            hex
        }
        val newest = shas.takeLast(max).toSet()
        assertEquals(newest, h.store.list().map { it.name.removeSuffix(".json") }.toSet())
        assertEquals(newest, h.served.all.value.map { it.key.desc }.toSet())
    }

    @Test
    fun loadKeepsOnlyTheNewestTemplates() = runTest {
        val h = harness()
        val max = BoardDescriptors.MAX_TEMPLATES
        val shas = (0 until max + 2).map { n ->
            val served = template(dict).replace("served-board-v1", "served-board-$n").encodeToByteArray()
            ParquetVerify.sha256Hex(served).also { h.store.write("$it.json", served) }
        }
        assertEquals(max, h.descriptors.load())
        assertEquals(shas.takeLast(max).toSet(), h.store.list().map { it.name.removeSuffix(".json") }.toSet())
        assertEquals(max, h.served.all.value.size)
    }

    @Test
    fun absentOrMalformedDescNeverDownloads() = runTest {
        val h = harness()
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(desc = null)) { h.downloads++; bytes })
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(desc = "abc")) { h.downloads++; bytes })
        assertEquals(DescriptorOutcome.Unsupported, h.descriptors.ensure(info.copy(dict = null)) { h.downloads++; bytes })
        assertEquals(0, h.downloads)
    }

    /** A digest with [sha]'s 16 hex prefix and a different rest, what a footer of a different template says. */
    private val samePrefixOtherDigest = sha.take(16) + sha.drop(16).map { if (it == '0') '1' else '0' }.joinToString("")

    @Test
    fun servedTemplateContradictedByAFooterIsEvictedAndNotFetchedAgain() = runTest {
        val h = harness()
        assertIs<DescriptorOutcome.Fetched>(h.descriptors.ensure(info) { h.downloads++; bytes })
        assertEquals(1, h.descriptors.confirm(samePrefixOtherDigest))
        assertTrue(h.store.list().isEmpty())
        assertTrue(h.served.all.value.isEmpty())
        // The next ensure downloads again, and the same bytes now differ from the footer.
        assertIs<DescriptorOutcome.Rejected>(h.descriptors.ensure(info) { h.downloads++; bytes })
        assertEquals(2, h.downloads)
        assertTrue(h.store.list().isEmpty())
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun uppercaseFooterDigestIsTakenAsLowercase() = runTest {
        val h = harness()
        h.descriptors.ensure(info) { bytes }
        assertEquals(1, h.descriptors.confirm(samePrefixOtherDigest.uppercase()))
        assertTrue(h.store.list().isEmpty())
        assertTrue(h.served.all.value.isEmpty())
    }

    @Test
    fun failedDiskDeleteStillUnservesAndTheNextConfirmRetries() = runTest {
        val h = harness()
        h.descriptors.ensure(info) { bytes }
        h.store.failDeletes = 1
        assertFailsWith<IllegalStateException> { h.descriptors.confirm(samePrefixOtherDigest) }
        assertTrue(h.served.all.value.isEmpty())
        assertEquals(1, h.store.list().size)
        // The digest was not taken, so the same footer tries the disk again.
        assertEquals(1, h.descriptors.confirm(samePrefixOtherDigest))
        assertTrue(h.store.list().isEmpty())
        assertIs<DescriptorOutcome.Rejected>(h.descriptors.ensure(info) { bytes })
    }

    @Test
    fun cachedTemplateContradictedBeforeItLoadsIsDeleted() = runTest {
        val h = harness()
        h.store.write("$sha.json", bytes)
        assertEquals(1, h.descriptors.confirm(samePrefixOtherDigest))
        assertEquals(0, h.descriptors.load())
        assertTrue(h.store.list().isEmpty())
    }

    @Test
    fun footerWithTheSameDigestKeepsTheTemplate() = runTest {
        val h = harness()
        val fetched = assertIs<DescriptorOutcome.Fetched>(h.descriptors.ensure(info) { h.downloads++; bytes })
        assertEquals(0, h.descriptors.confirm(sha))
        assertEquals(0, h.descriptors.confirm(sha.uppercase()))
        assertEquals(listOf("$sha.json"), h.store.list().map { it.name })
        assertEquals(listOf(fetched.template), h.served.all.value)
        assertEquals(DescriptorOutcome.Known, h.descriptors.ensure(info) { h.downloads++; bytes })
        assertEquals(1, h.downloads)
    }

    @Test
    fun footerOfAnotherPrefixChangesNothing() = runTest {
        val h = harness()
        h.descriptors.ensure(info) { bytes }
        assertEquals(0, h.descriptors.confirm("f".repeat(64)))
        // Not a digest, though each shares the prefix or matches every prefix.
        assertEquals(0, h.descriptors.confirm(""))
        assertEquals(0, h.descriptors.confirm(sha.take(16)))
        assertEquals(0, h.descriptors.confirm(sha.take(16) + "g".repeat(48)))
        assertEquals(0, h.descriptors.confirm("g".repeat(64)))
        assertEquals(1, h.store.list().size)
        assertEquals(1, h.served.all.value.size)
    }

    @Test
    fun bundledTemplatesAreNeverEvicted() = runTest {
        val bundled = BoardTemplate.parse(template(dict), desc = sha)
        val store = MemoryBlockStore()
        val served = ServedTemplates()
        // A cached copy of the bundled bytes, as a device that served them before the app bundled them leaves.
        store.write("$sha.json", bytes)
        val descriptors = BoardDescriptors(store, served, listOf(bundled), StandardTestDispatcher(testScheduler))
        assertEquals(1, descriptors.load())
        assertEquals(0, descriptors.confirm(samePrefixOtherDigest))
        assertEquals(listOf("$sha.json"), store.list().map { it.name })
        assertEquals(1, served.all.value.size)
        assertEquals(DescriptorOutcome.Known, descriptors.ensure(info) { error("bundled must not download") })
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

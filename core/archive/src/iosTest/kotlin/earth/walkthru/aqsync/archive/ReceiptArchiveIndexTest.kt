@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/** The in-memory side of the iOS receipt index, over real receipts in a temporary directory. */
class ReceiptArchiveIndexTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/receipt-index-test-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val stations = listOf("53315f5f-cb85-4d8d-b623-d56266084189", "11111111-2222-3333-4444-555555555555")

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun newIndex(): ReceiptArchiveIndex = ReceiptArchiveIndex(finals).also { IosFiles.prepareRoot(finals) }

    private fun record(station: String, i: Int, indexed: Boolean = false) = ArchiveRecord(
        station = station,
        name = "station=$station/year=2026/month=09/day=${10 + i % 20}/data_${(1000 + i).toString().padStart(4, '0')}_a_1-2-1.parquet",
        bytes = i.toLong(),
        crc32 = 1,
        sha256 = "s",
        syncedAtEpochMs = 1,
        sourceDevice = "d",
        indexedAtEpochMs = if (indexed) 1 else null,
    )

    private val order = compareBy<ArchiveRecord> { it.name }.thenBy { it.station }

    @Test
    fun concurrentPutsAllLandAndTheSnapshotStaysSorted() = runTest {
        val index = newIndex()
        val records = (0 until 64).map { record(stations[it % 2], it) }.shuffled(Random(7))
        withContext(Dispatchers.Default) { records.map { r -> async { index.put(r) } }.awaitAll() }
        val all = index.observeAll().first()
        assertEquals(records.sortedWith(order), all)
        for (r in records) assertEquals(r, index.get(r.key))
        assertEquals(records.filter { it.station == stations[1] }.sortedWith(order), index.observeStation(stations[1]).first())
        // The receipts on disk read back in the same order once their files exist.
        for (r in records) {
            val path = "$finals/${r.station}/${r.name}"
            IosFiles.mkdirs(path.substringBeforeLast('/'))
            IosFiles.writeAtomically(path, ByteArray(1))
        }
        assertEquals(all, ReceiptArchiveIndex(finals).observeAll().first())
    }

    @Test
    fun getAfterPutUpdateAndRemoveReadsTheLatest() = runTest {
        val index = newIndex()
        val r = record(stations[0], 1)
        assertNull(index.get(r.key))
        index.put(r)
        assertEquals(r, index.get(r.key))
        index.update(r.key) { it.copy(indexedAtEpochMs = 9) }
        assertEquals(9L, index.get(r.key)?.indexedAtEpochMs)
        // The same name under another station is another record.
        assertNull(index.get(ArchiveKey(stations[1], r.name)))
        index.remove(r.key)
        assertNull(index.get(r.key))
        assertTrue(index.observeAll().first().isEmpty())
    }

    @Test
    fun snapshotWritesKeepTheOrderAndTheNeverIndexedCount() {
        val random = Random(11)
        var snapshot = ReceiptArchiveIndex.Snapshot.of(emptyList())
        val expected = HashMap<ArchiveKey, ArchiveRecord>()
        repeat(2_000) { step ->
            val r = record(stations[random.nextInt(2)], random.nextInt(500), indexed = random.nextBoolean())
            if (step % 5 == 4) {
                snapshot = snapshot.without(r.key)
                expected.remove(r.key)
            } else {
                snapshot = snapshot.with(r)
                expected[r.key] = r
            }
        }
        assertEquals(expected.values.sortedWith(order), snapshot.all)
        assertEquals(expected.values.count { it.indexedAtEpochMs == null }, snapshot.neverIndexed)
        val absent = record(stations[0], 9_999)
        assertSame(snapshot, snapshot.without(absent.key))
    }

    /**
     * A lookup in 10,000 records costs about what it costs in 100 (log N, about 13 steps against 7).
     * A linear scan would be about 100 times slower, so the bound below is far from both.
     */
    @Test
    fun lookupDoesNotGrowWithTheIndex() {
        fun timeLookups(n: Int): Duration {
            val records = (0 until n).map { record(stations[it % 2], it) }
            val snapshot = ReceiptArchiveIndex.Snapshot.of(records.shuffled(Random(3)))
            val keys = (0 until 20_000).map { records[(it * 7_919) % n].key }
            repeat(2) { keys.forEach { k -> snapshot[k] } }
            return measureTime { keys.forEach { k -> check(snapshot[k] != null) } }
        }
        val small = timeLookups(100)
        val large = timeLookups(10_000)
        assertTrue(large < small * 10 + 50.milliseconds, "10,000 records took $large, 100 took $small")
    }
}

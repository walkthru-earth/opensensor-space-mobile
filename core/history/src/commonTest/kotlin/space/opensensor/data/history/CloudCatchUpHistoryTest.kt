package space.opensensor.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.data.history.parquet.ByteArrayReader
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.data.sync.CatchUpDecision
import space.opensensor.data.sync.CloudCatchUp
import space.opensensor.data.sync.CloudPrefixes
import space.opensensor.data.sync.ConflictReason
import space.opensensor.data.sync.RemoteFile
import space.opensensor.data.sync.SkipReason
import space.opensensor.model.FileChecksum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #273 acceptance. A cloud file that is already in the archive, the same name and checksum, is
 * skipped and adds no rows. The archive keeps one record and one file, and the History tiers hold the
 * same buckets and rows before and after the catch up. The same name with other bytes is a conflict
 * that changes nothing. Runs on the JVM and the iOS simulator.
 */
class CloudCatchUpHistoryTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val dayStart = 1_789_603_200L // 2026-09-17T00:00Z
    private val name = "thing=$thing/year=2026/month=09/day=17/data_0930_a_2-106-2.parquet"
    private val board = fixtureBytes("parquet/device/v3-board-2rg-105rows.parquet")
    private val sha = ParquetVerify.sha256Hex(board)
    private val prefixes = CloudPrefixes(raw = "walkthru-earth/opensensor-space", compacted = "walkthru-earth/opensensor-space-daily")

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

    private suspend fun archiveOneFile(): Pair<Store, Index> {
        val store = Store()
        val index = Index()
        val record = ArchiveRecord(thing, name, board.size.toLong(), crc32 = 1, sha256 = sha, syncedAtEpochMs = 1, sourceDevice = "ble")
        store.files[record.key] = board
        index.put(record)
        ArchiveIndexRepair(store, index, nowEpochMs = { 42L }, templates = CoreS3Files).backfillAll()
        return store to index
    }

    /** Every metric's hourly buckets of the day, with their row counts. */
    private suspend fun ArchiveHistoryTiers.snapshot(): Map<String, List<TierBucket>> =
        observeMetricsWithData(thing).first().associateWith { observe(thing, it, Resolution.HOUR, dayStart, dayStart + 86_400).first() }

    private fun Map<String, List<TierBucket>>.rows(): Long = values.sumOf { buckets -> buckets.sumOf { it.rows.toLong() } }

    @Test
    fun aCloudFileWithTheSameNameAndChecksumIsSkippedAndAddsNoRows() = runTest {
        val (store, index) = archiveOneFile()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val before = tiers.snapshot()
        val recordBefore = index.rows.value.single()
        assertTrue(before.rows() > 0)
        assertEquals(1, tiers.decodedDays)

        val catchUp = CloudCatchUp(index)
        val remote = RemoteFile("walkthru-earth/opensensor-space/$name", board.size.toLong(), FileChecksum.multihash(sha))
        val plan = catchUp.plan(thing, prefixes, listOf(remote))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), plan.entries.single().decision)
        assertTrue(plan.toFetch.isEmpty())
        assertEquals(1, catchUp.markUploaded(plan, atEpochMs = 77))

        // One record and one file still, the same bytes, now marked uploaded.
        assertEquals(listOf(recordBefore.copy(uploadedAtEpochMs = 77)), index.rows.value)
        assertEquals(listOf(recordBefore.key), store.files.keys.toList())
        // The same buckets and row counts, and the marker rebuilt no day.
        assertEquals(before, tiers.snapshot())
        assertEquals(1, tiers.decodedDays)
        val fresh = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        assertEquals(before, fresh.snapshot())
        assertEquals(before.rows(), fresh.snapshot().rows())
    }

    @Test
    fun theSameNameWithOtherBytesIsAConflictThatChangesNothing() = runTest {
        val (store, index) = archiveOneFile()
        val tiers = ArchiveHistoryTiers(store, index, templates = CoreS3Files)
        val before = tiers.snapshot()
        val recordBefore = index.rows.value.single()

        val catchUp = CloudCatchUp(index)
        val remote = RemoteFile("walkthru-earth/opensensor-space/$name", board.size.toLong(), FileChecksum.multihash("0".repeat(64)))
        val plan = catchUp.plan(thing, prefixes, listOf(remote))
        assertEquals(CatchUpDecision.Conflict(ConflictReason.OTHER_CHECKSUM, recordBefore), plan.entries.single().decision)
        assertTrue(plan.toFetch.isEmpty())
        assertEquals(0, catchUp.markUploaded(plan, atEpochMs = 77))

        assertEquals(listOf(recordBefore), index.rows.value)
        assertEquals(before, tiers.snapshot())
    }
}

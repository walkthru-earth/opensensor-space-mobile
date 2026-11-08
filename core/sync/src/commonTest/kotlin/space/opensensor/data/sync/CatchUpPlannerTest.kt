package space.opensensor.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.FileIdentity
import space.opensensor.archive.PrunedFile
import space.opensensor.archive.PrunedFiles
import space.opensensor.model.FileChecksum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchUpPlannerTest {
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val other = "0f0e0d0c-0b0a-4908-8706-050403020100"
    private val boot = "b41d37a48c04031e9397e28ce48c2f43"
    private val prefixes = CloudPrefixes(raw = "opensensor-space", compacted = "opensensor-space-daily/")
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)

    private fun name(day: Int, hhmm: String, first: Int, t: String = thing) =
        "thing=$t/year=2026/month=09/day=${day.toString().padStart(2, '0')}/data_${hhmm}_${boot}_$first-${first + 59}-1.parquet"

    private fun raw(name: String, bytes: Long = 1000, sha: String? = null) =
        RemoteFile("opensensor-space/$name", bytes, sha?.let(FileChecksum::multihash))

    private fun compacted(day: Int, bytes: Long = 5000, sha: String? = null, part: String = "day") = RemoteFile(
        "opensensor-space-daily/thing=$thing/year=2026/month=09/day=${day.toString().padStart(2, '0')}/$part.parquet",
        bytes,
        sha?.let(FileChecksum::multihash),
    )

    private fun archived(name: String, bytes: Long = 1000, sha: String = shaA) =
        ArchiveRecord(thing, name, bytes, crc32 = 1, sha256 = sha, syncedAtEpochMs = 1, sourceDevice = "ble")

    private fun plan(listing: List<RemoteFile>, vararg records: ArchiveRecord, pruned: List<PrunedFile> = emptyList()) =
        CatchUpPlanner.plan(thing, prefixes, listing, records.toList(), pruned)

    private fun CatchUpPlan.decision(remote: RemoteFile) = entries.single { it.remote == remote }.decision

    @Test
    fun sameNameAndChecksumIsSkippedAndProvenUploaded() {
        val n = name(15, "1000", 0)
        val remote = raw(n, sha = shaA)
        val result = plan(listOf(remote), archived(n))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), result.decision(remote))
        assertTrue(result.toFetch.isEmpty())
        assertEquals(listOf(FileIdentity(thing, FileIdentity.itemIdOf(n), shaA)), result.uploaded)
    }

    @Test
    fun sameNameWithOtherChecksumIsAConflictThatKeepsTheArchivedCopy() {
        val n = name(15, "1000", 0)
        val remote = raw(n, sha = shaB)
        val local = archived(n, sha = shaA)
        val result = plan(listOf(remote), local)
        assertEquals(CatchUpDecision.Conflict(ConflictReason.OTHER_CHECKSUM, local), result.decision(remote))
        assertEquals(1, result.conflicts.size)
        assertTrue(result.toFetch.isEmpty())
        assertTrue(result.uploaded.isEmpty())
    }

    @Test
    fun sameNameWithoutChecksumIsKeptAtTheSameSizeAndAConflictAtAnother() {
        val n = name(15, "1000", 0)
        val same = raw(n, bytes = 1000)
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_NAME_UNPROVEN), plan(listOf(same), archived(n)).decision(same))
        val bigger = raw(n, bytes = 2000)
        val local = archived(n)
        assertEquals(CatchUpDecision.Conflict(ConflictReason.OTHER_SIZE, local), plan(listOf(bigger), local).decision(bigger))
        assertTrue(plan(listOf(same), archived(n)).uploaded.isEmpty())
    }

    @Test
    fun anOlderReceiptWithoutSha256IsNeverFetchedAgain() {
        val n = name(15, "1000", 0)
        val remote = raw(n, sha = shaA)
        val result = plan(listOf(remote), archived(n, sha = ""))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_NAME_UNPROVEN), result.decision(remote))
        assertTrue(result.uploaded.isEmpty())
    }

    @Test
    fun newFilesAreFetchedAndArchivedBytesUnderAnotherNameAreNot() {
        val fresh = raw(name(15, "1100", 60), sha = shaB)
        val unknown = raw(name(15, "1200", 120))
        val alias = raw(name(15, "1300", 180), sha = shaA)
        val result = plan(listOf(fresh, unknown, alias), archived(name(15, "1000", 0)))
        assertEquals(CatchUpDecision.Fetch, result.decision(fresh))
        assertEquals(CatchUpDecision.Fetch, result.decision(unknown))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_BYTES_OTHER_NAME), result.decision(alias))
        assertEquals(listOf(fresh, unknown), result.toFetch.map { it.remote })
    }

    @Test
    fun theListingNeverPlansASecondCopy() {
        val n = name(15, "1000", 0)
        val first = raw(n, sha = shaA)
        val again = RemoteFile("opensensor-space/thing=$thing/year=2026/month=09/day=15/copy/../x.parquet", 1000)
        val repeat = first.copy(key = "opensensor-space/" + n.replace("day=15", "day=16"))
        val sameBytes = raw(name(15, "1100", 60), sha = shaA)
        val result = plan(listOf(first, again, repeat, sameBytes))
        assertEquals(CatchUpDecision.Fetch, result.decision(first))
        assertEquals(CatchUpDecision.Skip(SkipReason.NOT_A_DATA_FILE), result.decision(again))
        assertEquals(CatchUpDecision.Skip(SkipReason.REPEAT_IN_LISTING), result.decision(repeat))
        assertEquals(CatchUpDecision.Skip(SkipReason.REPEAT_IN_LISTING), result.decision(sameBytes))
        assertEquals(1, result.toFetch.size)
    }

    @Test
    fun theListingWithTwoDifferentFilesForOneNameIsAConflictForBoth() {
        val n = name(15, "1000", 0)
        val one = raw(n, sha = shaA)
        val two = raw(n, sha = shaB).copy(key = "opensensor-space/" + n.replace("day=15", "day=16"))
        val result = plan(listOf(one, two))
        assertEquals(CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES), result.decision(one))
        assertEquals(CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES), result.decision(two))
        assertTrue(result.toFetch.isEmpty())
    }

    @Test
    fun rawAndCompactedForOneDayAreNeverBothPlanned() {
        val rawDay15 = raw(name(15, "1000", 0), sha = shaA)
        val compacted15 = compacted(15, sha = shaB)
        val compacted16 = compacted(16, sha = shaC)
        val compacted17 = compacted(17)
        val result = plan(listOf(rawDay15, compacted15, compacted16, compacted17), archived(name(16, "0900", 0), sha = shaB))
        assertEquals(CatchUpDecision.Fetch, result.decision(rawDay15))
        // Day 15 has a raw file in the listing, day 16 one in the archive. Their roll ups stay unread.
        assertEquals(CatchUpDecision.Skip(SkipReason.RAW_COVERS_DAY), result.decision(compacted15))
        assertEquals(CatchUpDecision.Skip(SkipReason.RAW_COVERS_DAY), result.decision(compacted16))
        assertEquals(CatchUpDecision.Fetch, result.decision(compacted17))
        val fetchedDays = result.toFetch.groupBy { it.utcDate }
        assertTrue(fetchedDays.values.all { day -> day.map { it.kind }.distinct().size == 1 })
        assertEquals(setOf("2026-09-15", "2026-09-17"), fetchedDays.keys)
    }

    @Test
    fun aRawFileTheListingSkipsStillCoversItsDay() {
        val n = name(15, "1000", 0)
        val remote = raw(n, sha = shaA)
        val roll = compacted(15)
        val result = plan(listOf(remote, roll), archived(n))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), result.decision(remote))
        assertEquals(CatchUpDecision.Skip(SkipReason.RAW_COVERS_DAY), result.decision(roll))
        assertTrue(result.toFetch.isEmpty())
    }

    @Test
    fun aDayWithSeveralCompactedFilesFetchesOneOnlyWhenTheyAreProvenEqual() {
        val one = compacted(17, sha = shaA)
        val repeat = compacted(17, sha = shaA, part = "copy")
        val equal = plan(listOf(one, repeat))
        assertEquals(CatchUpDecision.Fetch, equal.decision(one))
        assertEquals(CatchUpDecision.Skip(SkipReason.REPEAT_IN_LISTING), equal.decision(repeat))

        val unknown = compacted(18)
        val unknownToo = compacted(18, part = "copy")
        val unproven = plan(listOf(unknown, unknownToo))
        assertEquals(CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES), unproven.decision(unknown))
        assertEquals(CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES), unproven.decision(unknownToo))
    }

    @Test
    fun keysOutsideThisThingsDataFilesAreSkipped() {
        val item = RemoteFile("opensensor-space/thing=$thing/year=2026/month=09/day=15/data_1000.json", 900)
        val collection = RemoteFile("opensensor-space/thing=$thing/collection.json", 900)
        val unsynced = RemoteFile("opensensor-space/thing=$thing/unsynced/boot=$boot/data_x.parquet", 900)
        val elsewhere = RemoteFile("another-bucket/thing=$thing/year=2026/month=09/day=15/data.parquet", 900)
        val otherThing = raw(name(15, "1000", 0, t = other))
        val result = plan(listOf(item, collection, unsynced, elsewhere, otherThing))
        listOf(item, collection, unsynced, elsewhere).forEach {
            assertEquals(CatchUpDecision.Skip(SkipReason.NOT_A_DATA_FILE), result.decision(it))
        }
        assertEquals(CatchUpDecision.Skip(SkipReason.OTHER_THING), result.decision(otherThing))
        assertNull(result.entries.single { it.remote == elsewhere }.name)
        assertTrue(result.toFetch.isEmpty())
    }

    @Test
    fun aFileTheUserRemovedIsNotFetchedAgain() {
        val n = name(15, "1000", 0)
        val remote = raw(n, sha = shaA)
        val result = plan(listOf(remote), pruned = listOf(PrunedFile(thing, n, 1000, 1, 5)))
        assertEquals(CatchUpDecision.Skip(SkipReason.REMOVED_BY_USER), result.decision(remote))
    }

    @Test
    fun aNestedCompactedPrefixIsNotReadAsRaw() {
        val nested = CloudPrefixes(raw = "os", compacted = "os/daily")
        val roll = RemoteFile("os/daily/thing=$thing/year=2026/month=09/day=17/day.parquet", 5000)
        val result = CatchUpPlanner.plan(thing, nested, listOf(roll), emptyList())
        assertEquals(CloudFileKind.COMPACTED, result.entries.single().kind)
        assertEquals(CatchUpDecision.Fetch, result.entries.single().decision)
    }

    @Test
    fun markUploadedSetsTheMarkerOnceAndAddsNoRecords() = runTest {
        val index = MemoryArchiveIndex()
        val n = name(15, "1000", 0)
        index.put(archived(n))
        index.put(archived(name(15, "1100", 60), sha = shaB))
        val catchUp = CloudCatchUp(index, DeviceCoordinator())
        val listing = listOf(raw(n, sha = shaA), raw(name(15, "1100", 60), sha = shaC))
        val result = catchUp.plan(thing, prefixes, listing)
        val puts = index.puts

        assertEquals(1, catchUp.markUploaded(result, atEpochMs = 42))
        assertEquals(0, catchUp.markUploaded(result, atEpochMs = 99))
        assertEquals(2, index.records.size)
        assertEquals(42L, index.records.single { it.name == n }.uploadedAtEpochMs)
        assertNull(index.records.single { it.name != n }.uploadedAtEpochMs)
        assertEquals(puts + 1, index.puts)
    }

    private fun compactedName(day: Int) = compacted(day).key.removePrefix("opensensor-space-daily/")

    private fun archivedCompacted(day: Int, bytes: Long = 5000, sha: String = shaB) = archived(compactedName(day), bytes, sha)

    @Test
    fun compactedDayFilesAreMatchedByTheirFullNameNotTheirBareName() {
        // Day 16 in the archive, day 17 listed. The same bare name, other days, other files.
        val otherSha = compacted(17, sha = shaC)
        assertEquals(CatchUpDecision.Fetch, plan(listOf(otherSha), archivedCompacted(16)).decision(otherSha))

        val unchecksummed = compacted(17)
        assertEquals(CatchUpDecision.Fetch, plan(listOf(unchecksummed), archivedCompacted(16)).decision(unchecksummed))

        val removed = listOf(PrunedFile(thing, compactedName(16), 5000, 1, 5))
        assertEquals(CatchUpDecision.Fetch, plan(listOf(otherSha), pruned = removed).decision(otherSha))

        // The same day is still the same file.
        val same = compacted(16, sha = shaB)
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), plan(listOf(same), archivedCompacted(16)).decision(same))
        assertEquals(CatchUpDecision.Skip(SkipReason.REMOVED_BY_USER), plan(listOf(compacted(16, sha = shaC)), pruned = removed).entries.single().decision)
    }

    @Test
    fun aCompactedDayInTheArchiveSkipsTheRawFilesOfThatDay() {
        val sameDay = raw(name(17, "1000", 0), sha = shaA)
        val nextDay = raw(name(18, "1000", 0), sha = shaA)
        val roll = compacted(17, sha = shaB)
        val result = plan(listOf(sameDay, nextDay, roll), archivedCompacted(17))
        assertEquals(CatchUpDecision.Skip(SkipReason.COMPACTED_COVERS_DAY), result.decision(sameDay))
        assertEquals(CatchUpDecision.Fetch, result.decision(nextDay))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), result.decision(roll))
        assertEquals(listOf(nextDay), result.toFetch.map { it.remote })
    }

    @Test
    fun archivedRawFilesKeepTheirDayRawBesideAnArchivedCompactedFile() {
        val listed = raw(name(17, "1100", 60), sha = shaC)
        val roll = compacted(17, sha = shaB)
        val result = plan(listOf(listed, roll), archived(name(17, "1000", 0)), archivedCompacted(17))
        assertEquals(CatchUpDecision.Fetch, result.decision(listed))
        assertEquals(CatchUpDecision.Skip(SkipReason.RAW_COVERS_DAY), result.decision(roll))
    }

    @Test
    fun aRepeatedRawNamePlansTheCopyThatCarriesTheChecksum() {
        val n = name(15, "1000", 0)
        // The same board file twice, first without a checksum, then with one.
        val bare = raw(n)
        val proven = raw(n.replace("day=15", "day=16"), sha = shaA)
        val result = plan(listOf(bare, proven), archived(n))
        assertEquals(CatchUpDecision.Skip(SkipReason.REPEAT_IN_LISTING), result.decision(bare))
        assertEquals(CatchUpDecision.Skip(SkipReason.SAME_FILE), result.decision(proven))
        assertEquals(listOf(FileIdentity(thing, FileIdentity.itemIdOf(n), shaA)), result.uploaded)
    }

    @Test
    fun theCatchUpReadsRemovedFilesFromTheCoordinator() = runTest {
        val n = name(15, "1000", 0)
        val removed = object : PrunedFiles {
            override fun observeAll(): Flow<List<PrunedFile>> = flowOf(listOf(PrunedFile(thing, n, 1000, 1, 5)))
            override suspend fun put(file: PrunedFile) = Unit
            override suspend fun remove(key: ArchiveKey) = Unit
            override suspend fun clear(): Int = 0
        }
        val catchUp = CloudCatchUp(MemoryArchiveIndex(), DeviceCoordinator(removed))
        val result = catchUp.plan(thing, prefixes, listOf(raw(n, sha = shaA)))
        assertEquals(CatchUpDecision.Skip(SkipReason.REMOVED_BY_USER), result.entries.single().decision)
    }
}

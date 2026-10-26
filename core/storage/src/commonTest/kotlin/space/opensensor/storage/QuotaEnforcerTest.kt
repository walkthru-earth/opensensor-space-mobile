package space.opensensor.storage

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuotaEnforcerTest {
    private val base = "/data/app"
    private val derived = "$base/cache/derived/v1"
    private val roots = StorageRoots(
        mapOf(
            StorageRole.Archive to "$base/archive",
            StorageRole.Index to "$base/archive-index",
            StorageRole.ClockAnchors to "$base/clock-anchors",
            StorageRole.Partials to "$base/archive-partial",
            StorageRole.Derived to derived,
        ),
    )

    /** An in-memory tree of files, absolute path to (bytes, modified time). */
    private class FakeTree : StorageTree {
        val files = LinkedHashMap<String, Pair<Long, Long>>()
        val deleted = mutableListOf<String>()

        /** A delete of a matching path throws, like a file the OS holds open. */
        var failOn: (String) -> Boolean = { false }

        /** Listing throws while set, like a directory the OS cannot read for a moment. */
        var listFails = false

        fun file(path: String, bytes: Long, modified: Long) {
            files[path] = bytes to modified
        }

        override fun list(dir: String): List<StorageEntry> {
            if (listFails) throw IllegalStateException("unreadable")
            return files.filterKeys { it.startsWith("$dir/") }.map { (path, f) ->
                StorageEntry(path.removePrefix("$dir/"), false, f.first, f.second)
            }
        }

        override fun delete(path: String) {
            if (failOn(path)) throw IllegalStateException("busy")
            deleted += path
            files.keys.removeAll { it == path || it.startsWith("$path/") }
        }
    }

    /** Durable and Scratch files far larger than any quota. None of them may ever go. */
    private fun FakeTree.untouchable(): List<String> {
        val paths = listOf(
            "$base/archive/s1/year=2026/month=10/day=01/data.parquet",
            "$base/archive-index/s1/data.parquet.receipt",
            "$base/clock-anchors/s1/boot.parquet",
            "$base/archive-partial/s1/a.parquet.partial",
        )
        for (path in paths) file(path, 1L shl 30, 0L)
        return paths
    }

    @Test
    fun evictsTheOldestDayFilesFirstUntilUnderQuota() {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.file("$derived/s1/2026-10-03.parquet", 100, 30)
        tree.file("$derived/s1/2026-10-01.parquet", 100, 10)
        tree.file("$derived/s2/2026-10-01.parquet", 100, 20)
        tree.file("$derived/s1/2026-10-04.parquet", 100, 40)
        tree.file("$derived/s1/manifest.json", 50, 0)

        val report = QuotaEnforcer(roots, tree).enforce(260)

        // 450 bytes over a 260 byte quota: the two oldest day files go, the manifest stays though it is oldest.
        assertEquals(listOf("$derived/s1/2026-10-01.parquet", "$derived/s2/2026-10-01.parquet"), tree.deleted)
        assertEquals(QuotaEnforcer.Report(bytesBefore = 450, bytesAfter = 250, evicted = 2), report)
        assertTrue("$derived/s1/manifest.json" in tree.files)
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun tiersShareTheQuotaAndClearRemovesThem() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.file("$derived/s1/day=2026-10-01.tiers", 100, 10)
        tree.file("$derived/s1/day=2026-10-02.tiers", 100, 20)
        tree.file("$derived/s1/day=2026-10-02.parquet", 100, 30)
        tree.file("$derived/s1/day=2026-10-02.manifest.json", 10, 0)
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 250 }
        assertEquals(1, quota.enforce().evicted)
        assertEquals(listOf("$derived/s1/day=2026-10-01.tiers"), tree.deleted)
        assertEquals(2, quota.clear().evicted)
        assertEquals(listOf("$derived/s1/day=2026-10-02.manifest.json"), tree.files.keys.filter { it.startsWith(derived) })
        for (path in untouchable) assertTrue(path in tree.files)
    }

    @Test
    fun aDerivedRootUnderTheQuotaIsLeftAlone() {
        val tree = FakeTree()
        tree.file("$derived/s1/2026-10-01.parquet", 100, 10)

        val report = QuotaEnforcer(roots, tree).enforce(100)

        assertTrue(tree.deleted.isEmpty())
        assertEquals(QuotaEnforcer.Report(bytesBefore = 100, bytesAfter = 100), report)
    }

    @Test
    fun manifestsAndOtherFilesStayEvenWhenTheyAloneExceedTheQuota() {
        val tree = FakeTree()
        tree.file("$derived/s1/manifest.json", 500, 0)
        tree.file("$derived/s1/2026-10-01.parquet.tmp", 500, 0)
        tree.file("$derived/s1/2026-10-01.parquet", 100, 10)

        val report = QuotaEnforcer(roots, tree).enforce(0)

        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.deleted)
        assertEquals(1, report.evicted)
        assertEquals(1000, report.bytesAfter)
    }

    @Test
    fun aDerivedRootInsideTheArchiveIsRefusedWhateverTheQuota() {
        // A misconfigured derived root inside a Durable one classifies as Durable, so nothing goes.
        val unsafe = StorageRoots(
            mapOf(
                StorageRole.Archive to "$base/archive",
                StorageRole.Derived to "$base/archive/derived",
            ),
        )
        val tree = FakeTree()
        tree.file("$base/archive/derived/s1/2026-10-01.parquet", 100, 10)
        tree.file("$base/archive/derived/s1/2026-10-02.parquet", 100, 20)

        val report = QuotaEnforcer(unsafe, tree).enforce(0)

        assertTrue(tree.deleted.isEmpty())
        assertEquals(QuotaEnforcer.Report(bytesBefore = 200, bytesAfter = 200, refused = 2), report)
    }

    @Test
    fun aFailedDeleteIsCountedAndTheNextFileGoes() {
        val tree = FakeTree()
        tree.failOn = { it.endsWith("2026-10-01.parquet") }
        tree.file("$derived/s1/2026-10-01.parquet", 100, 10)
        tree.file("$derived/s1/2026-10-02.parquet", 100, 20)
        tree.file("$derived/s1/2026-10-03.parquet", 100, 30)

        val report = QuotaEnforcer(roots, tree).enforce(150)

        assertEquals(QuotaEnforcer.Report(bytesBefore = 300, bytesAfter = 100, evicted = 2, failures = 1), report)
        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.list(derived).map { "$derived/${it.path}" })
    }

    @Test
    fun noDerivedRootIsAnEmptyReport() {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        val report = QuotaEnforcer(StorageRoots(mapOf(StorageRole.Archive to "$base/archive")), tree).enforce(0)
        assertEquals(QuotaEnforcer.Report(), report)
        assertTrue(tree.deleted.isEmpty())
        for (path in untouchable) assertTrue(path in tree.files)
    }

    @Test
    fun derivedQuotaEnforcesItsQuotaAndClearEvictsEveryDayFile() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.file("$derived/s1/2026-10-01.parquet", 100, 10)
        tree.file("$derived/s1/2026-10-02.parquet", 100, 20)
        tree.file("$derived/s1/manifest.json", 10, 0)
        var quotaBytes = 200L
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { quotaBytes }

        assertEquals(200L, quota.bytes())
        assertEquals(1, quota.enforce().evicted)
        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.deleted)

        quotaBytes = 1L shl 40
        val cleared = quota.clear()

        assertEquals(1, cleared.evicted)
        assertEquals(10, cleared.bytesAfter)
        assertEquals(listOf("$derived/s1/manifest.json"), tree.list(derived).map { "$derived/${it.path}" })
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun derivedQuotaDefaultsToTheRetentionPolicy() = runTest {
        assertEquals(RetentionPolicy.DEFAULT_DERIVED_QUOTA_BYTES, DerivedQuota(QuotaEnforcer(roots, FakeTree())).bytes())
    }

    /** Two day files of 100 bytes and a manifest, with a quota that keeps only the newer day. */
    private fun FakeTree.twoDays() {
        file("$derived/s1/2026-10-01.parquet", 100, 10)
        file("$derived/s1/2026-10-02.parquet", 100, 20)
        file("$derived/s1/manifest.json", 10, 0)
    }

    @Test
    fun nothingIsEvictedWhileAReaderHoldsTheDayFiles() = runTest {
        val tree = FakeTree()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 0 }

        val held = quota.holding {
            quota.holding { assertEquals(0, quota.clear().evicted) }
            assertTrue(tree.deleted.isEmpty(), "an inner hold ending evicts nothing while the outer one reads")
            assertEquals(0, quota.enforce().evicted)
            tree.files.size
        }

        assertEquals(3, held)
    }

    @Test
    fun theLastHoldEndingTrimsToQuotaWithNoFurtherCall() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 150 }

        assertEquals("zip", quota.holding { quota.holding { "zip" } })

        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.deleted)
        assertTrue("$derived/s1/manifest.json" in tree.files)
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun aFailedReaderStillTrimsWhenItsHoldEnds() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 150 }

        assertFailsWith<IllegalStateException> { quota.holding { error("read failed") } }

        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.deleted)
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun aCancelledReaderStillTrimsWhenItsHoldEnds() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 150 }

        val export = launch { quota.holding { awaitCancellation() } }
        runCurrent()
        assertEquals(0, quota.enforce().evicted)
        export.cancelAndJoin()

        assertEquals(listOf("$derived/s1/2026-10-01.parquet"), tree.deleted)
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun aClearDuringAHoldRunsWhenTheHoldEnds() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 1L shl 40 }

        quota.holding {
            assertEquals(0, quota.clear().evicted)
            assertTrue(tree.deleted.isEmpty())
        }

        assertEquals(listOf("$derived/s1/manifest.json"), tree.list(derived).map { "$derived/${it.path}" })
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
        // The deferred clear is spent, the next hold trims to the quota only.
        tree.file("$derived/s1/2026-10-03.parquet", 100, 30)
        quota.holding { }
        assertTrue("$derived/s1/2026-10-03.parquet" in tree.files)
    }

    @Test
    fun aDeferredClearSurvivesATrimThatFailsAtRelease() = runTest {
        val tree = FakeTree()
        val untouchable = tree.untouchable()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 1L shl 40 }

        quota.holding {
            quota.clear()
            tree.listFails = true
        }
        assertTrue(tree.deleted.isEmpty())

        // The clear is still owed, so the next hold that can list the folder clears instead of trimming to quota.
        tree.listFails = false
        quota.holding { }
        assertEquals(listOf("$derived/s1/manifest.json"), tree.list(derived).map { "$derived/${it.path}" })
        for (path in untouchable) assertTrue(path in tree.files, "kept $path")
    }

    @Test
    fun aDeferredClearSurvivingAFailedReleaseRunsOnTheNextEnforce() = runTest {
        val tree = FakeTree()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { 1L shl 40 }

        quota.holding {
            quota.clear()
            tree.listFails = true
        }
        tree.listFails = false
        quota.enforce()

        assertEquals(listOf("$derived/s1/manifest.json"), tree.list(derived).map { "$derived/${it.path}" })
        // Spent now, so the next enforce trims to the quota only.
        tree.file("$derived/s1/2026-10-03.parquet", 100, 30)
        quota.enforce()
        assertTrue("$derived/s1/2026-10-03.parquet" in tree.files)
    }

    @Test
    fun aTrimThatFailsAtReleaseLeavesTheReaderResult() = runTest {
        val tree = FakeTree()
        tree.twoDays()
        val quota = DerivedQuota(QuotaEnforcer(roots, tree)) { error("settings unreadable") }

        assertEquals(7, quota.holding { 7 })
        assertTrue(tree.deleted.isEmpty())
    }
}

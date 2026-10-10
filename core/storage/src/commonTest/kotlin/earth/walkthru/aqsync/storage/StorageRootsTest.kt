package earth.walkthru.aqsync.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageRootsTest {
    private val roots = StorageRoots(
        mapOf(
            StorageRole.Archive to "/data/files/archive",
            StorageRole.ClockAnchors to "/data/files/clock-anchors",
            StorageRole.Derived to "/data/files/derived/v1",
            StorageRole.Partials to "/data/files/archive-partial",
            StorageRole.Temp to "/data/files/tmp",
        )
    )

    @Test
    fun classifiesPathsByTheirRoot() {
        assertEquals(StorageClass.Durable, roots.classOf("/data/files/archive/station/a.parquet"))
        assertEquals(StorageClass.Durable, roots.classOf("/data/files/archive"))
        assertEquals(StorageClass.Scratch, roots.classOf("/data/files/archive-partial/station/a.parquet.partial"))
        assertEquals(StorageClass.Rebuildable, roots.classOf("/data/files/derived/v1/day.parquet"))
        assertNull(roots.classOf("/data/files/archived"))
        assertNull(roots.classOf("/elsewhere"))
        assertEquals(listOf("/data/files/archive", "/data/files/clock-anchors"), roots.directories(StorageClass.Durable))
        assertEquals("/data/files/tmp", roots.rootOf(StorageRole.Temp))
        assertNull(roots[StorageRole.Exports])
    }

    @Test
    fun durableWinsWhenAnotherRootNestsInsideIt() {
        val nested = StorageRoots(mapOf(StorageRole.Archive to "/a", StorageRole.Temp to "/a/tmp"))
        assertEquals(StorageClass.Durable, nested.classOf("/a/tmp/x"))
    }

    @Test
    fun refusesRelativeRoots() {
        assertFailsWith<IllegalArgumentException> { StorageRoots(mapOf(StorageRole.Temp to "tmp")) }
        assertFailsWith<IllegalArgumentException> { StorageRoots(mapOf(StorageRole.Temp to "/tmp/")) }
    }

    @Test
    fun fakeBudgetRefusesAReservationPastFreeSpace() = runTest {
        val budget = FakeStorageBudget(free = mapOf(StorageClass.Durable to 100L))
        budget.reserve(StorageClass.Durable, 100L)
        assertFailsWith<StorageException> { budget.reserve(StorageClass.Durable, 101L) }
        assertEquals(0L, budget.freeBytes(StorageClass.Scratch))
        assertEquals(listOf(StorageClass.Durable to 100L), budget.reservations)
        assertEquals(0L, budget.usage().getValue(StorageClass.Rebuildable))
    }
}

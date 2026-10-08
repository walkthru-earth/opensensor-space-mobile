package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.data.protocol.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A refresh of the device listing must never blank what the user already has: seen on the phone as
 * "LIST failed: link lost while waiting for a response" replacing the whole file list right after
 * "Synced n of n" (the post-sync `LIST` died with the link). The last [ManifestState.Loaded]
 * survives [ManifestState.Loading] and [ManifestState.Error] and is dropped only by a new session.
 */
class ManifestStateTest {

    private val files = listOf(Frame.File(4096, "archive/AQ-6b40/date=2026-09-16/part-0001.parquet"))
    private val end = Frame.ListEnd(count = 1, partials = 0, sdKib = 31_000_000, sdUsedKib = 12_000)
    private val loaded = ManifestState.Loaded(files, end, atEpochMs = 1_000L)

    @Test
    fun firstRefreshHasNothingToKeep() {
        val loading = ManifestState.Idle.refreshing()
        assertTrue(loading.isLoading)
        assertNull(loading.last)
        val failed = loading.failed("no response from device within 15 s")
        assertNull(failed.last)
        assertEquals("no response from device within 15 s", failed.error)
    }

    @Test
    fun aRefreshKeepsThePreviousListingWhileInFlight() {
        val loading = loaded.refreshing()
        assertTrue(loading.isLoading)
        assertSame(loaded, loading.last)
        assertNull(loading.error)
    }

    @Test
    fun aFailedRefreshKeepsThePreviousListingAndReportsWhy() {
        val failed = loaded.refreshing().failed("link lost while waiting for a response")
        assertFalse(failed.isLoading)
        assertSame(loaded, failed.last)
        assertEquals("link lost while waiting for a response", failed.error)
        // A retry from the failed state still carries the old listing.
        assertSame(loaded, failed.refreshing().last)
    }

    @Test
    fun aSuccessfulRefreshReplacesTheListingAndClearsTheError() {
        val newer = ManifestState.Loaded(files + Frame.File(8192, "archive/AQ-6b40/date=2026-09-16/part-0002.parquet"), end.copy(count = 2), 2_000L)
        val state = loaded.refreshing().failed("timeout").refreshing().loaded(newer.files, newer.end, newer.atEpochMs)
        assertEquals(newer, state)
        assertEquals(newer, state.last)
        assertNull(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun idleDropsEverything() {
        assertNull(ManifestState.Idle.last)
        assertNull(ManifestState.Idle.error)
        assertFalse(ManifestState.Idle.isLoading)
    }
}

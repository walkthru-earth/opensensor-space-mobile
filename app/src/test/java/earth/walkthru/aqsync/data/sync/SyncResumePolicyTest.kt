package earth.walkthru.aqsync.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect-after-lost-link decision (see the OnePlus finding in [SyncResumePolicy]): a sync
 * that was running, or that had just died of the pipe, is resumed; a user Stop, a completed run
 * (device `ERROR`s included) or an unrelated failure is not.
 */
class SyncResumePolicyTest {
    private val now = 1_758_000_000_000L

    @Test
    fun `a running sync is always resumed`() {
        assertTrue(SyncResumePolicy.shouldResume(running = true, endCause = null, finishedAtEpochMs = null, nowEpochMs = now))
        assertTrue(SyncResumePolicy.shouldResume(running = true, endCause = SyncEndCause.STOPPED, finishedAtEpochMs = now - 1, nowEpochMs = now))
    }

    @Test
    fun `a sync that just died of the transport is resumed`() {
        // "no response from device within 60 s", then the link is declared lost a moment later.
        assertTrue(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, now - 2_000, now))
        // TcpTransport declares the link dead after 180 s without a byte: still inside the window.
        assertTrue(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, now - 180_000, now))
        assertTrue(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, now - SyncResumePolicy.RESUME_WINDOW_MS, now))
    }

    @Test
    fun `an old transport failure is not tied to this link loss`() {
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, now - SyncResumePolicy.RESUME_WINDOW_MS - 1, now))
        // A clock that moved backwards must not look like "just now".
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, now + 10_000, now))
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.TRANSPORT_FAILURE, null, now))
    }

    @Test
    fun `user stop, completed runs and other failures are left alone`() {
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.STOPPED, now - 1_000, now))
        // Completed includes runs whose files ended in a device ERROR or a mismatch: those are outcomes, not a broken pipe.
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.COMPLETED, now - 1_000, now))
        assertFalse(SyncResumePolicy.shouldResume(false, SyncEndCause.FAILURE, now - 1_000, now))
        assertFalse(SyncResumePolicy.shouldResume(false, null, null, now))
    }
}

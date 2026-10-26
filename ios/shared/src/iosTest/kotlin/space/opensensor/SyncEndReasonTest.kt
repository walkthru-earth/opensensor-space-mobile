package space.opensensor

import space.opensensor.collection.CollectionFailure
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.StorageFailure
import space.opensensor.collection.SyncEndReason
import space.opensensor.collection.endReason
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.lan.LanTokenPolicy
import space.opensensor.data.sync.FileOutcome
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkTimeoutException
import space.opensensor.link.TimeoutPhase
import space.opensensor.link.UnavailableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEndReasonTest {
    private fun ended(e: TransportException): SyncResult {
        val failure = CollectionFailure.Link(e.disconnectCause())
        return partialResult(failure.syncEnd(), 3, emptyMap<String, FileOutcome>(), failure.endReason())
    }

    @Test
    fun aRadioThatIsOffIsNotWordedAsALostLink() {
        val result = ended(TransportUnavailableException(UnavailableKind.RADIO_OFF, "off"))
        assertEquals(SyncEnd.LINK_LOST, result.end)
        assertEquals(SyncEndReason.BLUETOOTH_OFF, result.endReason)
        assertEquals(SyncEndReason.PERMISSION_MISSING, ended(TransportUnavailableException(UnavailableKind.PERMISSION_MISSING, "denied")).endReason)
    }

    @Test
    fun aClosedLinkKeepsItsTypedCause() {
        val rotated = ended(LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED)))
        assertEquals(SyncEnd.AUTH_FAILED, rotated.end)
        assertEquals(SyncEndReason.TOKEN_ROTATED, rotated.endReason)
        val busy = ended(LinkClosedException(DisconnectCause.Busy))
        assertEquals(SyncEnd.BUSY, busy.end)
        assertEquals(SyncEndReason.DEVICE_BUSY, busy.endReason)
        assertEquals(SyncEndReason.OUT_OF_RANGE, ended(LinkClosedException(DisconnectCause.Unreachable)).endReason)
    }

    @Test
    fun aTimeoutIsOutOfRangeOnlyWhileOpening() {
        assertEquals(SyncEndReason.OUT_OF_RANGE, ended(LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0))).endReason)
        assertEquals(SyncEndReason.LINK_LOST, ended(LinkTimeoutException(DisconnectCause.Timeout(TimeoutPhase.INTER_FRAME, 0))).endReason)
        assertEquals(SyncEndReason.LINK_LOST, ended(TransportException("gone")).endReason)
    }

    @Test
    fun controllerFailuresMapToTheSharedReasons() {
        val noSpace = CollectionFailure.Storage(StorageFailure.NO_SPACE, 10)
        assertEquals(SyncEnd.STORAGE_FAILED, noSpace.syncEnd())
        assertEquals(SyncEndReason.NO_SPACE, noSpace.endReason())
        val interrupted = CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS)
        assertEquals(SyncEnd.STOPPED, interrupted.syncEnd())
        assertEquals(SyncEndReason.INTERRUPTED, interrupted.endReason())
        assertEquals(SyncEnd.FAILED, CollectionFailure.Unexpected("X").syncEnd())
    }

    @Test
    fun theStoredLanTokenDropsExactlyWhenTheSharedPolicySaysSo() {
        val policy = LanTokenPolicy()
        val causes = AuthKind.entries.map { DisconnectCause.Auth(it) } + listOf(
            DisconnectCause.Busy,
            DisconnectCause.Unreachable,
            DisconnectCause.PeerClosed(),
            DisconnectCause.Unavailable(UnavailableKind.RADIO_OFF),
        )
        for (cause in causes) {
            assertEquals(policy.invalidatesToken(cause), ended(LinkClosedException(cause)).dropsLanToken, cause.toString())
        }
        // An ERROR 14 refusal without a typed cause drops it too, a stop or lost link never does.
        assertTrue(SyncResult(SyncEnd.AUTH_FAILED, 0, 0, 0).dropsLanToken)
        assertFalse(SyncResult(SyncEnd.STOPPED, 0, 0, 0).dropsLanToken)
        assertFalse(SyncResult(SyncEnd.LINK_LOST, 0, 0, 0).dropsLanToken)
    }

    @Test
    fun aResultWithoutATypedCauseFallsBackToItsEnd() {
        assertEquals(SyncEndReason.COMPLETED, SyncResult(SyncEnd.COMPLETE, 1, 1, 0).endReason)
        assertEquals(SyncEndReason.STOPPED, SyncResult(SyncEnd.STOPPED, 1, 0, 0).endReason)
        assertEquals(SyncEndReason.TOKEN_REJECTED, SyncResult(SyncEnd.AUTH_FAILED, 0, 0, 0).endReason)
        assertFalse(SyncResult(SyncEnd.STOPPED, 1, 0, 0).resumable)
        assertTrue(SyncResult(SyncEnd.LINK_LOST, 1, 0, 0).resumable)
    }
}

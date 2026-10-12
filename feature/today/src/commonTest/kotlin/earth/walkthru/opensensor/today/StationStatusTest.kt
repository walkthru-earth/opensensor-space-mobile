package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.StationStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one busy and owner rule every station page reads, on both platforms. */
class StationStatusTest {
    @Test
    fun aReadyIdleLinkTakesCommandsAndShowsNoBanner() {
        val status = StationStatus(ConnectionState.Ready(247))
        assertTrue(status.canCommand)
        assertFalse(status.busy)
        assertFalse(status.showsBanner)
        assertFalse(status.offersReconnect)
    }

    @Test
    fun aRunningSyncIsBusy() {
        val status = StationStatus(ConnectionState.Ready(247), syncRunning = true)
        assertTrue(status.busy)
        assertFalse(status.canCommand)
    }

    @Test
    fun backgroundWorkShowsAWaitingBannerWithoutReconnect() {
        val closed = StationStatus(ConnectionState.Disconnected, heldInBackground = true)
        assertTrue(closed.busy)
        assertTrue(closed.showsBanner)
        assertFalse(closed.offersReconnect)
        val ready = StationStatus(ConnectionState.Ready(247), heldInBackground = true)
        assertTrue(ready.showsBanner)
        assertFalse(ready.canCommand)
    }

    @Test
    fun reconnectIsOfferedOnlyForAClosedOrFailedLinkNothingIsBringingBack() {
        assertTrue(StationStatus(ConnectionState.Disconnected).offersReconnect)
        assertTrue(StationStatus(ConnectionState.Failed("x")).offersReconnect)
        assertFalse(StationStatus(ConnectionState.Disconnected, reconnecting = true).offersReconnect)
        assertFalse(StationStatus(ConnectionState.Connecting).offersReconnect)
    }

    @Test
    fun todaySnapshotReadsTheSameStatus() {
        val snapshot = TodaySnapshot(connection = ConnectionState.Ready(247), heldInBackground = true)
        assertTrue(snapshot.station.busy)
        assertFalse(snapshot.station.canCommand)
    }
}

package space.opensensor.today

import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.ThingStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one busy and owner rule every thing page reads, on both platforms. */
class ThingStatusTest {
    @Test
    fun aReadyIdleLinkTakesCommandsAndShowsNoBanner() {
        val status = ThingStatus(ConnectionState.Ready(247))
        assertTrue(status.canCommand)
        assertFalse(status.busy)
        assertFalse(status.showsBanner)
        assertFalse(status.offersReconnect)
    }

    @Test
    fun aRunningSyncIsBusy() {
        val status = ThingStatus(ConnectionState.Ready(247), syncRunning = true)
        assertTrue(status.busy)
        assertFalse(status.canCommand)
    }

    @Test
    fun backgroundWorkShowsAWaitingBannerWithoutReconnect() {
        val closed = ThingStatus(ConnectionState.Disconnected, heldInBackground = true)
        assertTrue(closed.busy)
        assertTrue(closed.showsBanner)
        assertFalse(closed.offersReconnect)
        val ready = ThingStatus(ConnectionState.Ready(247), heldInBackground = true)
        assertTrue(ready.showsBanner)
        assertFalse(ready.canCommand)
    }

    @Test
    fun reconnectIsOfferedOnlyForAClosedOrFailedLinkNothingIsBringingBack() {
        assertTrue(ThingStatus(ConnectionState.Disconnected).offersReconnect)
        assertTrue(ThingStatus(ConnectionState.Failed("x")).offersReconnect)
        assertFalse(ThingStatus(ConnectionState.Disconnected, reconnecting = true).offersReconnect)
        assertFalse(ThingStatus(ConnectionState.Connecting).offersReconnect)
    }

    @Test
    fun todaySnapshotReadsTheSameStatus() {
        val snapshot = TodaySnapshot(connection = ConnectionState.Ready(247), heldInBackground = true)
        assertTrue(snapshot.thing.busy)
        assertFalse(snapshot.thing.canCommand)
    }
}

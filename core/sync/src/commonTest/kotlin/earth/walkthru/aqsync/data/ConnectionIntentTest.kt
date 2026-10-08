package earth.walkthru.aqsync.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionIntentTest {
    @Test
    fun queuedConnectCannotReopenAfterALaterDisconnect() = runTest {
        val intent = ConnectionIntent()
        val mutex = Mutex(locked = true)
        val actions = mutableListOf<String>()
        val connectClaim = intent.claim()
        val connect = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { if (intent.owns(connectClaim)) actions += "connect" }
        }
        val disconnectClaim = intent.claim()
        val disconnect = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { if (intent.owns(disconnectClaim)) actions += "disconnect" }
        }

        mutex.unlock()
        connect.await()
        disconnect.await()
        assertEquals(listOf("disconnect"), actions)
    }

    @Test
    fun laterExplicitConnectSupersedesAQueuedDisconnect() = runTest {
        val intent = ConnectionIntent()
        val mutex = Mutex(locked = true)
        val actions = mutableListOf<String>()
        val disconnectClaim = intent.claim()
        val disconnect = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { if (intent.owns(disconnectClaim)) actions += "disconnect" }
        }
        val connectClaim = intent.claim()
        val connect = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { if (intent.owns(connectClaim)) actions += "connect" }
        }

        mutex.unlock()
        disconnect.await()
        connect.await()
        assertEquals(listOf("connect"), actions)
    }
}

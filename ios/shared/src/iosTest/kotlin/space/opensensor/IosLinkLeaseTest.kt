@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package space.opensensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.Opcode
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cancellation and native request barriers on the physical connection's shared lease. */
class IosLinkLeaseTest {
    private fun sensor() = IosLink(
        object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) = done(true)
            override fun setReadPaused(paused: Boolean) = Unit
        },
        true,
        "synthetic-lease-test",
        Dispatchers.Default,
        Dispatchers.Default,
    )

    private fun op(req: ByteArray) = req[0].toInt() and 0xFF

    private fun IosLink.reply(frame: Frame) = offer(FrameCodec.encode(frame).toNSData())

    @Test
    fun aCancelAfterLockReturnsBeforeTheWaiterCompletesGivesItBack() = runBlocking {
        val link = sensor()
        val locked = CompletableDeferred<Unit>()
        link.afterLeaseLock = {
            locked.complete(Unit)
            // This cancels the async waiter after Mutex.lock has returned, so it cannot clean up.
            currentCoroutineContext().job.cancel()
        }
        try {
            val acquiring = launch(Dispatchers.Default) { link.acquire(LeaseKind.CONFIG).end() }
            withTimeout(5_000) { acquiring.join() }
            assertTrue(locked.isCompleted)
            assertTrue(acquiring.isCancelled)
            assertFalse(link.leased)
            link.afterLeaseLock = {}
            withTimeout(5_000) {
                link.leased(LeaseKind.CONFIG) { }
                link.leased(LeaseKind.COMMAND) { }
            }
        } finally {
            link.close()
        }
    }

    @Test
    fun aCallerCancelAfterLockReturnsGivesItBack() = runBlocking {
        val link = sensor()
        val caller = CompletableDeferred<Job>()
        val locked = CompletableDeferred<Unit>()
        link.afterLeaseLock = {
            locked.complete(Unit)
            caller.await().cancel()
        }
        try {
            val acquiring = launch(Dispatchers.Default) { link.acquire(LeaseKind.CONFIG).end() }
            caller.complete(acquiring)
            withTimeout(5_000) { acquiring.join() }
            assertTrue(locked.isCompleted)
            assertTrue(acquiring.isCancelled)
            assertFalse(link.leased)
            link.afterLeaseLock = {}
            withTimeout(5_000) { link.leased(LeaseKind.CONFIG) { } }
        } finally {
            link.close()
        }
    }

    @Test
    fun defaultDispatcherLeaseCancellationNeverLeaksOrOverlaps() = runBlocking {
        val link = sensor()
        val active = AtomicInt(0)
        val syncGrants = AtomicInt(0)
        suspend fun use(kind: LeaseKind) {
            try {
                link.leased(kind) {
                    assertEquals(1, active.incrementAndGet(), "leases overlapped")
                    if (kind == LeaseKind.SYNC) syncGrants.incrementAndGet()
                    try {
                        yield()
                    } finally {
                        assertEquals(0, active.decrementAndGet())
                    }
                }
            } catch (e: LeaseRefusedException) {
                assertTrue(e.syncRunning)
            }
        }
        try {
            repeat(300) { round ->
                withTimeout(5_000) {
                    val held = link.acquire(LeaseKind.DEBUG)
                    val waiters = List(6) { index ->
                        val kind = when {
                            index == 0 && round % 2 == 0 -> LeaseKind.SYNC
                            index == 5 -> LeaseKind.COMMAND
                            else -> LeaseKind.CONFIG
                        }
                        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { use(kind) }
                    }
                    val cancel = launch(Dispatchers.Default) {
                        waiters.filterIndexed { index, _ -> index > 0 && index % 2 == round % 2 }.forEach { it.cancel() }
                    }
                    held.end()
                    cancel.join()
                    waiters.forEach { it.join() }
                    assertFalse(link.leased, "round $round left a holder")
                    link.leased(LeaseKind.CONFIG) { }
                    link.leased(LeaseKind.COMMAND) { }
                }
            }
            assertEquals(0, active.value)
            assertEquals(150, syncGrants.value, "short syncs ran before queued leases")
        } finally {
            link.close()
        }
    }

    @Test
    fun aNativeLeaseWaitsForTheCancelledReadAndOwedClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<Int>()
        val port = object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) {
                sent += op(frame.toByteArray())
                done(true)
            }
            override fun setReadPaused(paused: Boolean) = Unit
        }
        val link = IosLink(port, true, "synthetic-native-quiet", dispatcher, dispatcher)
        try {
            val reading = launch { link.leased(LeaseKind.SYNC) { it.read(7, 0, 4096) { } } }
            runCurrent()
            assertEquals(listOf(Opcode.READ), sent)
            reading.cancelAndJoin()
            link.session.oweClose(7)
            val native = async { link.acquire(LeaseKind.NATIVE) }
            runCurrent()
            assertFalse(native.isCompleted, "native work waits for the READ drain")
            assertEquals(listOf(Opcode.READ, Opcode.ABORT), sent)

            link.reply(Frame.ReadEnd(7, 0, 0))
            runCurrent()
            assertFalse(native.isCompleted, "native work still waits for ABORTED")
            link.reply(Frame.Aborted(Opcode.READ))
            runCurrent()
            assertEquals(listOf(Opcode.READ, Opcode.ABORT, Opcode.CLOSE), sent)
            assertFalse(native.isCompleted, "native work waits for the owed CLOSED")

            link.reply(Frame.Closed(7))
            runCurrent()
            assertTrue(native.isCompleted)
            native.await().end()
            assertFalse(link.leased)
            link.leased(LeaseKind.CONFIG) { }
        } finally {
            link.close()
            runCurrent()
        }
    }

    @Test
    fun everyLeaseListenerHearsTheFinalDirectLeaseRelease() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) = done(true)
            override fun setReadPaused(paused: Boolean) = Unit
        }
        val link = IosLink(port, true, "synthetic-lease-listeners", dispatcher, dispatcher)
        // The heartbeat listens first, then the idle close. Both must hear each release.
        val heartbeat = mutableListOf<Boolean>()
        val idleClose = mutableListOf<Boolean>()
        link.onLeaseChange { heartbeat += it }
        link.onLeaseChange { idleClose += it }
        try {
            for (kind in listOf(LeaseKind.DEBUG, LeaseKind.CONFIG, LeaseKind.FILES)) {
                heartbeat.clear()
                idleClose.clear()
                val held = link.acquire(kind)
                runCurrent()
                held.end()
                runCurrent()
                assertEquals(false, heartbeat.lastOrNull(), "the heartbeat hears the $kind release")
                assertEquals(false, idleClose.lastOrNull(), "the idle close hears the $kind release")
            }
        } finally {
            link.close()
            runCurrent()
        }
    }

    @Test
    fun aLeaseQueuedBehindASyncIsRefusedEvenIfTheSyncEndsImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) = done(true)
            override fun setReadPaused(paused: Boolean) = Unit
        }
        val link = IosLink(port, true, "synthetic-quick-sync", dispatcher, dispatcher)
        try {
            val held = link.acquire(LeaseKind.DEBUG)
            val sync = launch(start = CoroutineStart.UNDISPATCHED) { link.leased(LeaseKind.SYNC) { } }
            val config = async(start = CoroutineStart.UNDISPATCHED) { runCatching { link.acquire(LeaseKind.CONFIG) } }
            held.end()
            runCurrent()
            sync.join()
            val refused = config.await().exceptionOrNull()
            assertTrue(refused is LeaseRefusedException && refused.syncRunning)
            assertFalse(link.leased)
            link.leased(LeaseKind.CONFIG) { }
        } finally {
            link.close()
            runCurrent()
        }
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import space.opensensor.archive.IosArchiveStore
import space.opensensor.collection.LinkPreference
import space.opensensor.data.archive.IosArchiveDatabase
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameCodec
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.ProtocolError
import space.opensensor.link.DisconnectCause
import space.opensensor.link.LinkClosedException
import space.opensensor.link.TimeoutPhase
import space.opensensor.storage.FakeStorageBudget
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Swift-facing runner and the [IosLink] leases over a scripted sensor, on the iOS archive. */
class SensorSyncTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/sensor-sync-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Default)
    private val database = IosArchiveDatabase(store, "$base/database")
    private val sync = SensorSync(store, database.index, Dispatchers.Default, Dispatchers.Default, progressInterval = 0, budget = FakeStorageBudget())
    private val thing = "53315f5f-cb85-4d8d-b623-d56266084189"

    @AfterTest
    fun cleanUp() {
        database.close()
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun parquet(seed: Int, size: Int): ByteArray = Random(seed).nextBytes(size).also {
        ParquetVerify.MAGIC.copyInto(it, 0)
        ParquetVerify.MAGIC.copyInto(it, size - ParquetVerify.MAGIC.size)
    }

    private val files = linkedMapOf(
        "thing=$thing/year=2026/month=09/day=15/data_1000_b41d_15000-15089-90.parquet" to parquet(1, 5_000),
        "thing=$thing/year=2026/month=09/day=15/data_1015_b41d_15090-15179-90.parquet" to parquet(2, 3_000),
    )

    /** Requests seen by the sensor, by opcode. */
    private val requests = ArrayList<Int>()

    /** The Swift side of the link, answering each request on one serial queue like the firmware. */
    private class FakePort(private val onRequest: (ByteArray) -> Unit) : IosLinkPort {
        private val serial = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        override val mtu = 517

        override fun send(frame: NSData, done: (Boolean) -> Unit) {
            val req = frame.toByteArray()
            serial.launch {
                done(true)
                onRequest(req)
            }
        }

        override fun setReadPaused(paused: Boolean) = Unit
    }

    /** A link whose sensor answers each request like the firmware, through [IosLink.offer]. */
    private fun sensor(inboxBudget: Long = 1L shl 20, answer: (IosLink, ByteArray) -> Boolean = { _, _ -> false }): IosLink {
        lateinit var link: IosLink
        val port = FakePort { req ->
            requests += req[0].toInt() and 0xFF
            if (!answer(link, req)) respond(link, req)
        }
        link = IosLink(port, overWifi = true, address = "a1b2c3d4e5f6", callbacks = Dispatchers.Default, context = Dispatchers.Default, inboxBudget = inboxBudget)
        return link
    }

    private fun IosLink.reply(frame: Frame) = offer(FrameCodec.encode(frame).toNSData())

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
    private var open: String? = null

    private fun respond(link: IosLink, req: ByteArray) {
        when (val op = req[0].toInt() and 0xFF) {
            Opcode.STATUS -> link.offerStatus("{\"utc\":0,\"clk\":0}")
            Opcode.SET_TIME -> link.reply(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000))
            Opcode.LIST -> {
                files.forEach { (name, bytes) -> link.reply(Frame.File(bytes.size.toLong(), name)) }
                link.reply(Frame.ListEnd(files.size.toLong(), 0, 0, 0))
            }
            Opcode.OPEN -> {
                val name = req.copyOfRange(1, req.size).decodeToString()
                val bytes = files.getValue(name)
                open = name
                link.reply(Frame.Opened(1, bytes.size.toLong(), ParquetVerify.crc32(bytes), name))
            }
            Opcode.READ -> {
                val bytes = files.getValue(open!!)
                val offset = u32(req, 3)
                val end = minOf(bytes.size.toLong(), offset + u32(req, 7))
                var pos = offset
                while (pos < end) {
                    val n = minOf(400L, end - pos).toInt()
                    link.reply(Frame.Chunk(1, pos, bytes.copyOfRange(pos.toInt(), pos.toInt() + n)))
                    pos += n
                }
                link.reply(Frame.ReadEnd(1, pos, 0))
            }
            Opcode.CLOSE -> link.reply(Frame.Closed(1))
            Opcode.ABORT -> link.reply(Frame.Aborted(0))
            else -> link.reply(Frame.Error(op, ProtocolError.MALFORMED_REQUEST.code, ""))
        }
    }

    private fun run(link: IosLink, progress: MutableList<SyncProgress> = ArrayList()): SyncResult = runBlocking {
        val done = CompletableDeferred<SyncResult>()
        sync.start(link, thing, "a1b2c3d4e5f6", 4096, { progress += it }) { done.complete(it) }
        withTimeout(20_000) { done.await() }
    }

    @Test
    fun everyFileIsSavedThroughTheLink() {
        val progress = ArrayList<SyncProgress>()
        val result = run(sensor(), progress)

        assertEquals(SyncEnd.COMPLETE, result.end)
        assertEquals(listOf(Opcode.STATUS, Opcode.SET_TIME), requests.take(2))
        assertEquals(2, result.files)
        assertEquals(2, result.saved)
        assertTrue(result.keepsLink)
        assertEquals(2, progress.last().filesDone)
        files.forEach { (name, bytes) ->
            val saved = NSFileManager.defaultManager.contentsAtPath("$finals/$thing/$name")!!.toByteArray()
            assertContentEquals(bytes, saved)
        }
        assertFalse(Opcode.SET_CONFIG in requests || Opcode.REBOOT in requests)
    }

    @Test
    fun aSecondSyncOnlyLists() {
        run(sensor())
        requests.clear()

        val result = run(sensor())

        assertEquals(SyncEnd.COMPLETE, result.end)
        assertEquals(0, result.saved)
        assertEquals(listOf(Opcode.STATUS, Opcode.SET_TIME, Opcode.LIST), requests)
    }

    @Test
    fun aConnectionAlreadySynchronizedDoesNotSetTimeAgain() {
        val link = sensor { link, req ->
            (req[0].toInt() and 0xFF == Opcode.STATUS).also {
                if (it) link.offerStatus("{\"utc\":1,\"clk\":1,\"clk_age_s\":60}")
            }
        }
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        assertEquals(listOf(Opcode.STATUS, Opcode.LIST), requests.take(2))
        assertFalse(Opcode.SET_TIME in requests)
    }

    @Test
    fun networkClockSurvivesANewNativeLinkAndRepeatedSync() {
        fun healthy() = sensor { link, req ->
            (req[0].toInt() and 0xFF == Opcode.STATUS).also {
                if (it) link.offerStatus("{\"utc\":1,\"clk\":3,\"clk_age_s\":60}")
            }
        }
        assertEquals(SyncEnd.COMPLETE, run(healthy()).end)
        requests.clear()
        assertEquals(SyncEnd.COMPLETE, run(healthy()).end)
        assertEquals(listOf(Opcode.STATUS, Opcode.LIST), requests)
    }

    @Test
    fun automaticClockCheckDoesNotCreateAnAcknowledgementReceiptForASkippedUpdate() = runBlocking {
        var anchorCount = 0
        val runner = SensorSync(
            store,
            database.index,
            Dispatchers.Default,
            Dispatchers.Default,
            recordClockAnchor = { _, _, _, _ -> anchorCount++ },
            budget = FakeStorageBudget(),
        )
        val link = sensor { link, req ->
            (req[0].toInt() and 0xFF == Opcode.STATUS).also {
                if (it) link.offerStatus("{\"utc\":1,\"clk\":1,\"clk_age_s\":60}")
            }
        }.also { it.identify(thing, "same-boot", 1024) }
        val done = CompletableDeferred<SyncResult>()
        runner.start(link, thing, "a1b2c3d4e5f6", 4096, {}) { done.complete(it) }
        assertEquals(SyncEnd.COMPLETE, withTimeout(20_000) { done.await() }.end)
        assertEquals(0, anchorCount)
        assertFalse(Opcode.SET_TIME in requests)
    }

    @Test
    fun theAcknowledgedAnchorKeepsTheFullThingAndBootIdentity() = runBlocking {
        data class Anchor(val thing: String, val boot: String, val utc: Long, val mono: Long)
        val anchors = mutableListOf<Anchor>()
        val runner = SensorSync(
            store,
            database.index,
            Dispatchers.Default,
            Dispatchers.Default,
            recordClockAnchor = { st, boot, utc, mono -> anchors += Anchor(st, boot, utc, mono) },
            budget = FakeStorageBudget(),
        )
        val link = sensor().also { it.identify(thing, "full-boot-identity", 1024) }
        val done = CompletableDeferred<SyncResult>()
        runner.start(link, thing, "a1b2c3d4e5f6", 4096, {}) { done.complete(it) }
        assertEquals(SyncEnd.COMPLETE, withTimeout(20_000) { done.await() }.end)
        assertEquals(listOf(Anchor(thing, "full-boot-identity", 1_789_581_931_123_000_000L, 12_345_678L)), anchors)
    }

    @Test
    fun refusedUtcStillPreservesOriginalArchiveFiles() {
        val link = sensor { link, req ->
            (req[0].toInt() and 0xFF == Opcode.SET_TIME).also {
                if (it) link.reply(Frame.Error(Opcode.SET_TIME, 9, "clock unavailable"))
            }
        }
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        assertEquals(listOf(Opcode.STATUS, Opcode.SET_TIME), requests.take(2))
        assertTrue(Opcode.LIST in requests)
    }

    @Test
    fun aClosedLinkEndsTheSyncAsLost() {
        val result = run(sensor { link, req -> (req[0].toInt() and 0xFF == Opcode.READ).also { if (it) link.linkLost(IosLink.REASON_PEER_CLOSED) } })

        assertEquals(SyncEnd.LINK_LOST, result.end)
        assertTrue(result.resumable)
        assertFalse(result.keepsLink)
    }

    @Test
    fun aRejectedTokenAndABusySensorAreTyped() {
        val auth = run(sensor { link, _ -> link.reply(Frame.Error(0, ProtocolError.AUTH_FAILED.code, "")).let { true } })
        assertEquals(SyncEnd.AUTH_FAILED, auth.end)

        val busy = run(sensor { link, req -> link.reply(Frame.Error(req[0].toInt() and 0xFF, ProtocolError.BUSY.code, "")).let { true } })
        assertEquals(SyncEnd.BUSY, busy.end)
    }

    @Test
    fun aTokenRefusedMidSyncEndsAsAuthFailed() {
        var opens = 0
        val result = run(
            sensor { link, req ->
                (req[0].toInt() and 0xFF == Opcode.OPEN && ++opens == 2).also {
                    if (it) link.reply(Frame.Error(0, ProtocolError.AUTH_FAILED.code, ""))
                }
            },
        )

        assertEquals(SyncEnd.AUTH_FAILED, result.end)
        assertEquals(2, opens)
    }

    @Test
    fun aThingSyncsOverOneLinkAtATime() = runBlocking {
        val first = CompletableDeferred<SyncResult>()
        val second = CompletableDeferred<SyncResult>()
        val quiet = sensor { _, _ -> true }
        val handle = sync.start(quiet, thing, "a1b2c3d4e5f6", 4096, {}) { first.complete(it) }
        sync.start(sensor(), thing, "a1b2c3d4e5f6", 4096, {}) { second.complete(it) }

        assertEquals(SyncEnd.IN_USE, withTimeout(5_000) { second.await() }.end)
        handle.cancel()
        assertEquals(SyncEnd.STOPPED, withTimeout(5_000) { first.await() }.end)
    }

    @Test
    fun aCancelBeforeTheWorkStartsStillFinishesOnceAndFreesTheThing() = runBlocking {
        // Work waits in this queue until the test runs it, so cancel() lands before dispatch.
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val held = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.trySend(block)
            }
        }
        val gated = SensorSync(store, database.index, held, Dispatchers.Default, progressInterval = 0, budget = FakeStorageBudget())
        suspend fun pumpUntil(done: CompletableDeferred<*>) = withTimeout(20_000) {
            while (!done.isCompleted) {
                queue.tryReceive().getOrNull()?.run() ?: yield()
            }
        }

        val calls = AtomicInt(0)
        val first = CompletableDeferred<SyncResult>()
        gated.start(sensor(), thing, "a1b2c3d4e5f6", 4096, {}) {
            calls.incrementAndGet()
            first.complete(it)
        }.cancel()
        pumpUntil(first)
        assertEquals(SyncEnd.STOPPED, first.await().end)

        val second = CompletableDeferred<SyncResult>()
        gated.start(sensor(), thing, "a1b2c3d4e5f6", 4096, {}) { second.complete(it) }
        pumpUntil(second)
        assertEquals(SyncEnd.COMPLETE, second.await().end)
        assertEquals(1, calls.value)
    }

    @Test
    fun aFileThisPhoneCannotStoreFailsAloneAndKeepsTheLink() {
        val (blocked, other) = files.keys.toList()
        // A directory where the archived file belongs, so the move into the archive fails.
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$finals/$thing/$blocked", true, null, null))

        val result = run(sensor())

        assertEquals(SyncEnd.STORAGE_FAILED, result.end)
        assertTrue(result.keepsLink)
        assertEquals(1, result.saved)
        assertEquals(1, result.notSaved)
        assertContentEquals(files.getValue(other), NSFileManager.defaultManager.contentsAtPath("$finals/$thing/$other")!!.toByteArray())
    }

    @Test
    fun aProtocolFaultFailsTheSyncAndKeepsTheLink() {
        val result = run(
            sensor { link, req ->
                (req[0].toInt() and 0xFF == Opcode.READ).also {
                    if (it) link.reply(Frame.Chunk(9, 0, ByteArray(16)))
                }
            },
        )

        assertEquals(SyncEnd.FAILED, result.end)
        assertTrue(result.keepsLink)
    }

    private fun op(req: ByteArray) = req[0].toInt() and 0xFF

    @Test
    fun theSameLinkChecksTheClockOnce() {
        val link = sensor()
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        requests.clear()

        assertEquals(SyncEnd.COMPLETE, run(link).end)
        assertEquals(listOf(Opcode.LIST), requests)
    }

    @Test
    fun aCancelMidReadDrainsAndClosesBeforeTheNextRun() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        var quiet = true
        val link = sensor { link, req ->
            when (op(req)) {
                Opcode.READ -> quiet.also { if (it) reading.complete(Unit) }
                Opcode.ABORT -> link.reply(Frame.Aborted(Opcode.READ)).let { true }
                else -> false
            }
        }
        val first = CompletableDeferred<SyncResult>()
        val handle = sync.start(link, thing, "a1b2c3d4e5f6", 4096, {}) { first.complete(it) }
        withTimeout(5_000) { reading.await() }
        handle.cancel()
        assertEquals(SyncEnd.STOPPED, withTimeout(5_000) { first.await() }.end)

        quiet = false
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        val after = requests.drop(requests.indexOf(Opcode.READ) + 1)
        assertEquals(listOf(Opcode.ABORT, Opcode.CLOSE, Opcode.LIST), after.take(3))
    }

    @Test
    fun aCancelledCommandEndsStopped() = runBlocking {
        val setting = CompletableDeferred<Unit>()
        val link = sensor { link, req ->
            when (op(req)) {
                Opcode.SET_TIME -> setting.complete(Unit).let { true }
                Opcode.ABORT -> link.reply(Frame.Aborted(Opcode.SET_TIME)).let { true }
                else -> false
            }
        }
        val done = CompletableDeferred<CommandResult>()
        val handle = sync.command(link, SensorCommand.SET_TIME) { done.complete(it) }
        withTimeout(5_000) { setting.await() }
        handle.cancel()
        val result = withTimeout(5_000) { done.await() }
        assertTrue(result.stopped)
        assertFalse(result.clockSynchronized)
        assertTrue(link.isOpen)
    }

    @Test
    fun framesOfferedBeforeTheLinkWasLostStillArrive() = runBlocking {
        val link = sensor { link, req ->
            (op(req) == Opcode.LIST).also {
                if (it) {
                    files.forEach { (name, bytes) -> link.reply(Frame.File(bytes.size.toLong(), name)) }
                    link.reply(Frame.ListEnd(files.size.toLong(), 0, 0, 0))
                    link.linkLost(IosLink.REASON_PEER_CLOSED)
                }
            }
        }
        val listed = withTimeout(5_000) { link.leased(LeaseKind.FILES) { it.list() } }
        assertEquals(files.keys.toList(), listed.first.map { it.name })
        withTimeout(5_000) { while (link.isOpen) yield() }
    }

    @Test
    fun theInboxPausesReadingAtHalfItsBudgetAndResumesBelowAQuarter() {
        val pauses = ArrayList<Boolean>()
        val port = object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) = done(true)
            override fun setReadPaused(paused: Boolean) {
                pauses += paused
            }
        }
        val link = IosLink(port, true, "a1b2c3d4e5f6", Dispatchers.Default, Dispatchers.Default, inboxBudget = 4_000)
        var level = 0L
        link.inboxLevel = { level }
        listOf(1_000L, 2_000L, 2_500L, 1_500L, 999L, 10L).forEach {
            level = it
            link.onInboxLevel(it)
        }
        assertEquals(listOf(true, false), pauses)
        link.close()
    }

    @Test
    fun aStaleInboxReportNeitherPausesNorResumesReading() {
        val pauses = ArrayList<Boolean>()
        val port = object : IosLinkPort {
            override val mtu = 517
            override fun send(frame: NSData, done: (Boolean) -> Unit) = done(true)
            override fun setReadPaused(paused: Boolean) {
                pauses += paused
            }
        }
        val link = IosLink(port, true, "a1b2c3d4e5f6", Dispatchers.Default, Dispatchers.Default, inboxBudget = 4_000)
        var level = 0L
        link.inboxLevel = { level }
        // A high report that lost the race to the read that emptied the inbox.
        link.onInboxLevel(3_000)
        assertEquals(emptyList(), pauses)
        level = 3_000
        link.onInboxLevel(3_000)
        // A low report that lost the race to frames that filled it again.
        link.onInboxLevel(10)
        assertEquals(listOf(true), pauses)
        level = 0
        link.onInboxLevel(0)
        assertEquals(listOf(true, false), pauses)
        link.close()
    }

    @Test
    fun leasesShareOneSessionAndASecondSyncIsRefused() = runBlocking {
        val link = sensor()
        val debug = link.leased(LeaseKind.DEBUG) { it }
        assertSame(debug, link.leased(LeaseKind.DEBUG) { it })
        assertFalse(link.leased)

        val sync = link.acquire(LeaseKind.SYNC)
        assertTrue(link.syncRunning)
        assertTrue(assertFailsWith<LeaseRefusedException> { link.acquire(LeaseKind.SYNC) }.syncRunning)
        assertTrue(assertFailsWith<LeaseRefusedException> { link.acquire(LeaseKind.CONFIG) }.syncRunning)
        sync.end()
        sync.end()
        assertFalse(link.leased)
        link.leased(LeaseKind.CONFIG) { }
    }

    @Test
    fun aCancelledLeaseTakesNothingAndGivesBackWhatItHeld() = runBlocking {
        val link = sensor()
        val held = link.acquire(LeaseKind.DEBUG)
        // Undispatched, so the waiter is parked on the lock before the cancel.
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { link.leased(LeaseKind.CONFIG) { error("never granted") } }
        waiting.cancel()
        waiting.join()
        held.end()
        assertFalse(link.leased)

        val holding = launch(Dispatchers.Default) { link.leased(LeaseKind.FILES) { awaitCancellation() } }
        withTimeout(5_000) { while (!link.leased) yield() }
        holding.cancel()
        holding.join()
        assertFalse(link.leased)
        withTimeout(5_000) { link.leased(LeaseKind.DEBUG) { } }
    }

    @Test
    fun aClosedLinkRefusesLeasesAndTellsSwiftOnce() = runBlocking {
        val link = sensor()
        val closed = CompletableDeferred<Unit>()
        val calls = AtomicInt(0)
        link.whenClosed {
            calls.incrementAndGet()
            closed.complete(Unit)
        }
        link.close()
        link.close()
        withTimeout(5_000) { closed.await() }
        assertFalse(link.isOpen)
        assertFalse(assertFailsWith<LeaseRefusedException> { link.acquire(LeaseKind.DEBUG) }.syncRunning)
        link.whenClosed { calls.incrementAndGet() }
        assertEquals(2, calls.value)
    }

    @Test
    fun theProviderLendsASyncLeaseAndReleaseGivesItBackOnce() = runBlocking {
        val link = sensor().also { it.identify(thing, "boot", 1024) }
        val provider = IosLinkProvider({ link }, 5.seconds, Dispatchers.Default)
        val lent = provider.connect("a1b2c3d4e5f6", LinkPreference.ANY)
        assertEquals(thing, lent.thing)
        assertEquals(1024, lent.maxRead)
        assertTrue(link.syncRunning)
        lent.release()
        lent.release()
        assertFalse(link.leased)
        assertEquals(DisconnectCause.Unreachable, assertFailsWith<LinkClosedException> { provider.connect("a1b2c3d4e5f6", LinkPreference.BLE) }.disconnectCause)
    }

    @Test
    fun aProviderConnectTimesOutOrCancelsAndLendsNothing() = runBlocking {
        val link = sensor().also { it.identify(thing, "boot", 1024) }
        val held = link.acquire(LeaseKind.DEBUG)
        val provider = IosLinkProvider({ link }, 200.milliseconds, Dispatchers.Default)
        val timedOut = assertFailsWith<LinkClosedException> { provider.connect("a1b2c3d4e5f6", LinkPreference.ANY) }
        assertEquals(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0), timedOut.disconnectCause)

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            IosLinkProvider({ link }, 5.seconds, EmptyCoroutineContext).connect("a1b2c3d4e5f6", LinkPreference.ANY)
        }
        waiting.cancel()
        waiting.join()
        held.end()
        assertFalse(link.leased)
        withTimeout(5_000) { link.leased(LeaseKind.DEBUG) { } }
    }

    @Test
    fun aCancelRightAfterTheProviderTookTheLeaseGivesItBack() = runBlocking {
        val link = sensor().also { it.identify(thing, "boot", 1024) }
        val took = CompletableDeferred<Unit>()
        val provider = IosLinkProvider({ link }, 5.seconds, EmptyCoroutineContext, granted = {
            took.complete(Unit)
            currentCoroutineContext().job.cancel()
        })
        val connecting = launch(Dispatchers.Default) { provider.connect("a1b2c3d4e5f6", LinkPreference.ANY) }
        connecting.join()
        assertTrue(took.isCompleted)
        assertTrue(connecting.isCancelled)
        assertFalse(link.leased)
        withTimeout(5_000) { link.leased(LeaseKind.DEBUG) { } }
    }

    @Test
    fun aLeaseQueuedBehindASyncIsRefusedWhenTheSyncStarts() = runBlocking {
        val link = sensor()
        val debug = link.acquire(LeaseKind.DEBUG)
        val sync = async(start = CoroutineStart.UNDISPATCHED) { link.acquire(LeaseKind.SYNC) }
        val config = async(start = CoroutineStart.UNDISPATCHED) { runCatching { link.acquire(LeaseKind.CONFIG) } }
        val command = async(start = CoroutineStart.UNDISPATCHED) { link.acquire(LeaseKind.COMMAND) }
        debug.end()
        val refused = withTimeout(5_000) { config.await() }.exceptionOrNull()
        assertTrue(refused is LeaseRefusedException && refused.syncRunning, "config was $refused")
        val held = withTimeout(5_000) { sync.await() }
        assertTrue(link.syncRunning)
        assertFalse(command.isCompleted, "a command still waits its turn behind the sync")
        held.end()
        withTimeout(5_000) { command.await() }.end()
        assertFalse(link.leased)
    }
}

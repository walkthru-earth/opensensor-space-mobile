@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.RetryPolicy
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.ble.TransportUnavailableException
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.TimeoutPhase
import earth.walkthru.aqsync.link.UnavailableKind
import earth.walkthru.aqsync.storage.FakeStorageBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The shared controller on iOS (#81), over a scripted board that Swift reconnects to through dial. */
@OptIn(ExperimentalAtomicApi::class)
class SensorCollectionsTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/sensor-collections-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Default)
    private val anchors = ArrayList<Long>()
    private val anchorLock = NSLock()
    private val record: suspend (String, String, Long, Long) -> Unit = { _, _, nanos, _ ->
        anchorLock.lock()
        anchors += nanos
        anchorLock.unlock()
    }
    private val sync = SensorSync(
        store,
        ReceiptArchiveIndex(finals, Dispatchers.Default),
        Dispatchers.Default,
        Dispatchers.Default,
        progressInterval = 0,
        recordClockAnchor = record,
        budget = FakeStorageBudget(),
    )
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"
    private val device = "a1b2c3d4e5f6"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun parquet(seed: Int, size: Int): ByteArray = Random(seed).nextBytes(size).also {
        ParquetVerify.MAGIC.copyInto(it, 0)
        ParquetVerify.MAGIC.copyInto(it, size - ParquetVerify.MAGIC.size)
    }

    private val files = linkedMapOf(
        "station=$station/year=2026/month=09/day=15/data_1000_b41d_15000-15089-90.parquet" to parquet(1, 5_000),
        "station=$station/year=2026/month=09/day=15/data_1015_b41d_15090-15179-90.parquet" to parquet(2, 3_000),
    )
    private val second = files.keys.last()

    /**
     * One sensor across connections. Its clock stays anchored once `SET_TIME` landed. [onRead]
     * decides each `READ` of a file name, true when it handled the request itself.
     */
    private inner class Board(val onRead: (IosLink, String) -> Boolean = { _, _ -> false }) {
        private val lock = NSLock()
        private var open: String? = null
        var anchored = false
        val lists = AtomicInt(0)
        val timeSets = AtomicInt(0)
        val dials = AtomicInt(0)
        var current: IosLink? = null

        /** What Swift answers a dial when it cannot open one, such as Bluetooth off. */
        var refuseAs: DialResult? = null

        /** When set, a dial waits here for the test to answer it, like a reconnect still before INFO. */
        var held: CompletableDeferred<(DialResult) -> Unit>? = null

        fun connect(wifi: Boolean = true): IosLink {
            lateinit var link: IosLink
            link = IosLink(Port { answer(link, it) }, overWifi = wifi, address = device, callbacks = Dispatchers.Default, context = Dispatchers.Default)
            link.identify(station, "boot", 1024)
            current = link
            return link
        }

        /** Swift's reconnect, a fresh identified link. */
        fun dial(name: String, prefer: LinkPreference, done: (DialResult) -> Unit) {
            assertEquals(device, name)
            dials.incrementAndFetch()
            refuseAs?.let { return done(it) }
            held?.let {
                it.complete(done)
                return
            }
            connect()
            done(DialResult.OPENED)
        }

        private fun answer(link: IosLink, req: ByteArray) {
            fun reply(frame: Frame) = link.offer(FrameCodec.encode(frame).toNSData())
            when (val op = req[0].toInt() and 0xFF) {
                Opcode.STATUS -> link.offerStatus(if (anchored) "{\"utc\":1,\"clk\":1,\"clk_age_s\":60}" else "{\"utc\":0,\"clk\":0}")
                Opcode.SET_TIME -> {
                    timeSets.incrementAndFetch()
                    anchored = true
                    reply(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000))
                }
                Opcode.LIST -> {
                    lists.incrementAndFetch()
                    files.forEach { (name, bytes) -> reply(Frame.File(bytes.size.toLong(), name)) }
                    reply(Frame.ListEnd(files.size.toLong(), 0, 0, 0))
                }
                Opcode.OPEN -> {
                    val name = req.copyOfRange(1, req.size).decodeToString()
                    val bytes = files.getValue(name)
                    lock.lock()
                    open = name
                    lock.unlock()
                    reply(Frame.Opened(1, bytes.size.toLong(), ParquetVerify.crc32(bytes), name))
                }
                Opcode.READ -> {
                    lock.lock()
                    val name = open!!
                    lock.unlock()
                    if (onRead(link, name)) return
                    val bytes = files.getValue(name)
                    val offset = u32(req, 3)
                    val end = minOf(bytes.size.toLong(), offset + u32(req, 7))
                    var pos = offset
                    while (pos < end) {
                        val n = minOf(400L, end - pos).toInt()
                        reply(Frame.Chunk(1, pos, bytes.copyOfRange(pos.toInt(), pos.toInt() + n)))
                        pos += n
                    }
                    reply(Frame.ReadEnd(1, pos, 0))
                }
                Opcode.CLOSE -> reply(Frame.Closed(1))
                Opcode.ABORT -> reply(Frame.Aborted(0))
                else -> reply(Frame.Error(op, ProtocolError.MALFORMED_REQUEST.code, ""))
            }
        }
    }

    /** The Swift side of one connection, answering on one serial queue like the firmware. */
    private class Port(private val onRequest: (ByteArray) -> Unit) : IosLinkPort {
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

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private fun collections(
        board: Board,
        keepAlive: KeepAlive = KeepAlive.None,
        callbacks: CoroutineContext = Dispatchers.Default,
        retry: RetryPolicy = RetryPolicy(firstBackoff = 10.milliseconds, maxBackoff = 50.milliseconds),
    ) = SensorCollections(
        sync,
        IosLinkProvider({ board.current }, 5.seconds, Dispatchers.Default, board::dial, poll = 10.milliseconds),
        record,
        Dispatchers.Default,
        callbacks,
        keepAlive = keepAlive,
        retry = retry,
        progressInterval = 0,
    )

    /** Every callback of one request, in the order they arrived. */
    private class Seen {
        private val lock = NSLock()
        val events = ArrayList<String>()
        val done = CompletableDeferred<SyncResult>()
        val finishes = AtomicInt(0)

        fun progress(update: SyncProgress) {
            lock.lock()
            events += "progress ${update.filesDone}"
            lock.unlock()
        }

        fun finished(result: SyncResult) {
            lock.lock()
            events += "finished"
            lock.unlock()
            finishes.incrementAndFetch()
            done.complete(result)
        }
    }

    private fun SensorCollections.collect(seen: Seen) = collect(device, null, LinkPreference.ANY, false, seen::progress, seen::finished)

    @Test
    fun theProviderDialsWhenNoLinkIsOpenAndLendsTheNewOne() = runBlocking {
        val board = Board()
        val provider = IosLinkProvider({ board.current }, 5.seconds, Dispatchers.Default, board::dial, poll = 10.milliseconds)
        val lent = provider.connect(device, LinkPreference.ANY)
        assertEquals(1, board.dials.load())
        assertEquals(station, lent.station)
        assertTrue(board.current!!.syncRunning)
        lent.release()
        assertFalse(board.current!!.leased)

        val silent = IosLinkProvider({ null }, 100.milliseconds, Dispatchers.Default, { _, _, _ -> }, poll = 10.milliseconds)
        val timedOut = assertFailsWith<LinkClosedException> { silent.connect(device, LinkPreference.ANY) }
        assertEquals(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0), timedOut.disconnectCause)
    }

    @Test
    fun aRefusedDialFailsAtOnceInsteadOfWaitingTheOpenTimeout() = runBlocking {
        val dials = AtomicInt(0)
        val provider = IosLinkProvider(
            { null },
            5.seconds,
            Dispatchers.Default,
            { _, _, done -> dials.incrementAndFetch().also { done(DialResult.REFUSED) } },
            poll = 1.seconds,
        )
        val began = TimeSource.Monotonic.markNow()
        val refused = assertFailsWith<LinkClosedException> { provider.connect(device, LinkPreference.ANY) }
        assertEquals(DisconnectCause.Unreachable, refused.disconnectCause)
        assertTrue(began.elapsedNow() < 900.milliseconds, "failed after ${began.elapsedNow()}")
        // A refused dial is no longer in flight, so the next attempt asks Swift again.
        assertFailsWith<LinkClosedException> { provider.connect(device, LinkPreference.ANY) }
        assertEquals(2, dials.load())
    }

    @Test
    fun aDialWithTheRadioUnavailableFailsAsThatKind() = runBlocking {
        val kinds = mapOf(
            DialResult.RADIO_OFF to UnavailableKind.RADIO_OFF,
            DialResult.PERMISSION_MISSING to UnavailableKind.PERMISSION_MISSING,
            DialResult.NO_ADAPTER to UnavailableKind.NO_ADAPTER,
        )
        for ((answer, kind) in kinds) {
            val provider = IosLinkProvider({ null }, 5.seconds, Dispatchers.Default, { _, _, done -> done(answer) }, poll = 1.seconds)
            val failed = assertFailsWith<TransportUnavailableException> { provider.connect(device, LinkPreference.BLE) }
            assertEquals(kind, failed.kind)
        }
    }

    @Test
    fun aRunWithBluetoothOffEndsAtOnceAsBluetoothOffAndNeverDialsAgain() = runBlocking {
        val board = Board { _, _ -> false }
        board.refuseAs = DialResult.RADIO_OFF
        val runs = collections(board)
        val seen = Seen()
        runs.collect(seen)
        val result = withTimeout(10_000) { seen.done.await() }
        assertEquals(SyncEndReason.BLUETOOTH_OFF, result.endReason)
        assertFalse(result.dropsLanToken)
        repeat(20) { yield() }
        assertEquals(1, board.dials.load(), "a radio that is off is not retried")
    }

    @Test
    fun attemptsWhileADialIsInFlightWaitForItInsteadOfDiallingAgain() = runBlocking {
        val board = Board()
        val answer = CompletableDeferred<(DialResult) -> Unit>()
        val dials = AtomicInt(0)
        val provider = IosLinkProvider(
            { board.current },
            5.seconds,
            Dispatchers.Default,
            { _, _, done -> dials.incrementAndFetch().also { answer.complete(done) } },
            poll = 10.milliseconds,
        )
        val first = async(Dispatchers.Default) { runCatching { provider.connect(device, LinkPreference.ANY) } }
        val second = async(Dispatchers.Default) { runCatching { provider.connect(device, LinkPreference.ANY) } }
        val done = withTimeout(5_000) { answer.await() }
        repeat(10) { yield() }
        board.connect()
        done(DialResult.OPENED)
        val results = listOf(first, second).awaitAll()
        assertEquals(1, dials.load())
        // One lends the new link, the other finds its sync lease taken.
        val lent = results.mapNotNull { it.getOrNull() }
        assertEquals(1, lent.size)
        assertEquals(DisconnectCause.Busy, (results.firstNotNullOf { it.exceptionOrNull() } as LinkClosedException).disconnectCause)
        lent.single().release()
        assertFalse(board.current!!.leased)
    }

    @Test
    fun aCancelDuringADialLeavesItInFlightAndTheRetryLendsItsLink() = runBlocking {
        val board = Board()
        val answer = CompletableDeferred<(DialResult) -> Unit>()
        val dials = AtomicInt(0)
        val provider = IosLinkProvider(
            { board.current },
            5.seconds,
            Dispatchers.Default,
            { _, _, done -> dials.incrementAndFetch().also { answer.complete(done) } },
            poll = 10.milliseconds,
        )
        val cancelled = launch(Dispatchers.Default) { provider.connect(device, LinkPreference.ANY) }
        val done = withTimeout(5_000) { answer.await() }
        cancelled.cancel()
        withTimeout(1_000) { cancelled.join() }
        assertTrue(cancelled.isCancelled)

        val retry = async(Dispatchers.Default) { provider.connect(device, LinkPreference.ANY) }
        repeat(10) { yield() }
        board.connect()
        done(DialResult.OPENED)
        val lent = withTimeout(5_000) { retry.await() }
        assertEquals(1, dials.load(), "the retry waits for the dial Swift is still making")
        assertEquals(station, lent.station)
        lent.release()
        assertFalse(board.current!!.leased)
    }

    @Test
    fun anAppStopDuringTheRetryWaitEndsTheRunAndNeverDialsAgain() = runBlocking {
        val dropped = CompletableDeferred<IosLink>()
        val board = Board { link, name ->
            (name == second && !dropped.isCompleted).also {
                if (it) {
                    link.linkLost(IosLink.REASON_PEER_CLOSED)
                    dropped.complete(link)
                }
            }
        }
        board.connect()
        // The retry waits ten minutes, so the run is still waiting when the stop comes.
        val runs = collections(board, retry = RetryPolicy(firstBackoff = 10.minutes, maxBackoff = 10.minutes))
        val seen = Seen()
        runs.collect(seen)
        val lost = withTimeout(10_000) { dropped.await() }
        withTimeout(10_000) { while (lost.leased) yield() }
        assertTrue(runs.isActive(device), "the run waits to retry")

        runs.stop("ffffffffffff")
        repeat(20) { yield() }
        assertTrue(runs.isActive(device), "a stop of another sensor leaves this run alone")

        runs.stop(device.uppercase())
        val result = withTimeout(5_000) { seen.done.await() }
        assertEquals(SyncEnd.STOPPED, result.end)
        assertEquals(SyncEndReason.STOPPED, result.endReason)
        assertEquals(1, result.saved, "the file saved before the loss counts")
        assertEquals(0, board.dials.load(), "a stopped run never dials again")
        assertFalse(runs.isActive(device))
        assertEquals(1, seen.finishes.load())
    }

    @Test
    fun anAppStopDuringADialEndsTheRunAndTheLateLinkIsNeverUsed() = runBlocking {
        val held = CompletableDeferred<(DialResult) -> Unit>()
        val board = Board()
        board.held = held
        // No link is open, so the run dials, like a reconnect that has not reached INFO.
        val runs = collections(board)
        val seen = Seen()
        runs.collect(seen)
        val done = withTimeout(10_000) { held.await() }
        assertTrue(runs.isActive(device))

        runs.stop(device)
        val result = withTimeout(5_000) { seen.done.await() }
        assertEquals(SyncEnd.STOPPED, result.end)
        assertFalse(runs.isActive(device))

        // The dial lands after the stop. Nothing lists or leases the new link.
        board.held = null
        val late = board.connect()
        done(DialResult.OPENED)
        delay(200.milliseconds)
        assertEquals(1, board.dials.load(), "a stopped run never dials again")
        assertEquals(0, board.lists.load(), "a stopped run never transfers")
        assertFalse(late.leased)
        assertEquals(1, seen.finishes.load())
    }

    @Test
    fun collectionsIsOneInstancePerRunner() {
        val board = Board()
        val first = sync.collections({ board.current }, board::dial)
        assertSame(first, sync.collections({ null }, { _, _, done -> done(DialResult.REFUSED) }))
    }

    @Test
    fun aLostLinkRetriesOnTheDialledOneFromAFreshListAndAnchorsTheClockOnce() = runBlocking {
        val lost = AtomicInt(0)
        val board = Board { link, name ->
            // The first connection drops in the middle of the second file.
            (name == second && lost.compareAndSet(0, 1)).also { if (it) link.linkLost(IosLink.REASON_PEER_CLOSED) }
        }
        board.connect()
        val seen = Seen()
        collections(board).collect(seen)
        val result = withTimeout(10_000) { seen.done.await() }
        assertEquals(SyncEnd.COMPLETE, result.end)
        assertEquals(2, result.files)
        assertEquals(2, result.saved, "files of both attempts count")
        assertEquals(1, board.dials.load())
        assertEquals(2, board.lists.load(), "every attempt plans from a fresh LIST")
        assertEquals(1, board.timeSets.load())
        assertEquals(listOf(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000).epochNanos), anchors)
        assertFalse(board.current!!.leased)
        assertEquals(1, seen.finishes.load())
        assertEquals("finished", seen.events.last())
    }

    @Test
    fun aBrokenBondEndsWithoutRetryAndCountsWhatItSaved() = runBlocking {
        val board = Board { link, name ->
            (name == second).also { if (it) link.linkLost(IosLink.REASON_BOND_MISSING) }
        }
        board.connect()
        val seen = Seen()
        collections(board).collect(seen)
        val result = withTimeout(10_000) { seen.done.await() }
        assertEquals(SyncEnd.AUTH_FAILED, result.end)
        assertEquals(SyncEndReason.PAIRING_REPAIR, result.endReason, "a broken bond is worded as pairing repair, not a lost link")
        assertEquals(2, result.files)
        assertEquals(1, result.saved)
        assertEquals(0, result.notSaved)
        assertEquals(0, board.dials.load(), "an auth failure never reconnects")
    }

    @Test
    fun aStopThenAnImmediateRestartRunsOneAfterTheOther() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val board = Board { _, name ->
            // The first READ of the first run never answers until it is stopped.
            (name != second && !held.isCompleted).also { if (it) held.complete(Unit) }
        }
        board.connect()
        val runs = collections(board)
        val first = Seen()
        val handle = runs.collect(first)
        withTimeout(10_000) { held.await() }

        val busy = Seen()
        runs.collect(busy)
        assertEquals(SyncEnd.IN_USE, withTimeout(10_000) { busy.done.await() }.end)

        handle.cancel()
        val next = Seen()
        runs.collect(next)
        val stopped = withTimeout(10_000) { first.done.await() }
        val done = withTimeout(10_000) { next.done.await() }
        assertEquals(SyncEnd.STOPPED, stopped.end)
        assertEquals(SyncEndReason.STOPPED, stopped.endReason, "a user stop is not an interruption")
        assertEquals(0, stopped.saved)
        assertEquals(SyncEnd.COMPLETE, done.end)
        assertEquals(2, done.saved)
        handle.cancel()
        // Late callbacks of the stopped run never reach it after its outcome.
        repeat(20) { yield() }
        assertEquals(1, first.finishes.load())
        assertEquals("finished", first.events.last())
        assertEquals(1, next.finishes.load())
        assertFalse(board.current!!.leased)
    }

    @Test
    fun anInterruptedRunKeepsWhatItSavedAndResumesOnceWhenTheAppIsBack() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val board = Board { _, name ->
            // The second file never answers in the first run, as when iOS suspends the app.
            (name == second && !held.isCompleted).also { if (it) held.complete(Unit) }
        }
        board.connect()
        val runs = collections(board)
        val first = Seen()
        runs.collect(device, null, LinkPreference.LAN, false, first::progress, first::finished)
        withTimeout(10_000) { held.await() }

        assertTrue(runs.interrupt(device))
        val interrupted = withTimeout(10_000) { first.done.await() }
        assertEquals(SyncEnd.STOPPED, interrupted.end)
        assertEquals(SyncEndReason.INTERRUPTED, interrupted.endReason)
        assertEquals(1, interrupted.saved, "the file saved before the interruption stays")
        assertFalse(runs.interrupt(device), "nothing left to interrupt")

        val resumed = runs.takeInterrupted()
        assertEquals(1, resumed.size)
        assertEquals(device, resumed[0].device)
        assertEquals(null, resumed[0].onlyName)
        assertEquals(LinkPreference.LAN, resumed[0].prefer)
        assertTrue(runs.takeInterrupted().isEmpty(), "a run resumes once")

        val next = Seen()
        runs.collect(resumed[0].device, resumed[0].onlyName, resumed[0].prefer, false, next::progress, next::finished)
        val done = withTimeout(10_000) { next.done.await() }
        assertEquals(SyncEnd.COMPLETE, done.end)
        assertEquals(1, done.saved, "only the file the interrupted run lacked")
        assertFalse(board.current!!.leased)
    }

    @Test
    fun anUnattendedRunIsNeverRememberedAndANewRunReplacesAnInterruptedOne() = runBlocking {
        val reads = Channel<Unit>(Channel.UNLIMITED)
        // Every READ of the second file goes unanswered, so each run waits there until it ends.
        val board = Board { _, name -> (name == second).also { if (it) reads.trySend(Unit) } }
        board.connect()
        val runs = collections(board)

        val unattended = Seen()
        val handle = runs.collect(device, null, LinkPreference.ANY, true, unattended::progress, unattended::finished)
        withTimeout(10_000) { reads.receive() }
        assertFalse(runs.interrupt(device), "a BGTask run ends through its own expiry")
        handle.cancel()
        assertEquals(SyncEnd.STOPPED, withTimeout(10_000) { unattended.done.await() }.end)

        val user = Seen()
        runs.collect(device, null, LinkPreference.ANY, false, user::progress, user::finished)
        withTimeout(10_000) { reads.receive() }
        assertTrue(runs.interrupt(device))
        withTimeout(10_000) { user.done.await() }

        val again = Seen()
        val next = runs.collect(device, null, LinkPreference.ANY, false, again::progress, again::finished)
        assertTrue(runs.takeInterrupted().isEmpty(), "the user's new run replaced the interrupted one")
        withTimeout(10_000) { reads.receive() }
        next.cancel()
        withTimeout(10_000) { again.done.await() }
        runs.interrupt(device)
        assertTrue(runs.takeInterrupted().isEmpty())
    }

    @Test
    fun aDirectSyncThatLosesItsLinkStillCountsTheFilesItSaved() = runBlocking {
        val board = Board { link, name ->
            (name == second).also { if (it) link.linkLost(IosLink.REASON_PEER_CLOSED) }
        }
        val link = board.connect()
        val done = CompletableDeferred<SyncResult>()
        sync.start(link, station, device, 1024, {}) { done.complete(it) }
        val result = withTimeout(10_000) { done.await() }
        assertEquals(SyncEnd.LINK_LOST, result.end)
        assertEquals(2, result.files)
        assertEquals(1, result.saved)
        assertEquals(0, result.notSaved)
    }

    @Test
    fun aRunIosGivesNoBackgroundTimeEndsBeforeItTouchesTheLink() = runBlocking {
        val board = Board()
        board.connect()
        val asked = ArrayList<Boolean>()
        val refusing = IosKeepAlive({ _, _, unattended -> asked += unattended; false }, {}, Dispatchers.Default)
        val runs = collections(board, refusing)
        val seen = Seen()
        runs.collect(device, null, LinkPreference.ANY, true, seen::progress, seen::finished)
        val result = withTimeout(10_000) { seen.done.await() }
        assertEquals(SyncEnd.STOPPED, result.end)
        assertEquals(SyncEndReason.INTERRUPTED, result.endReason)
        assertTrue(result.resumable)
        assertEquals(listOf(true), asked, "an unattended run says so")
        assertEquals(0, board.lists.load(), "a refused run never lists")
        assertFalse(board.current!!.leased)
        assertFalse(runs.isActive(device))
    }

    @Test
    fun aRunHoldsItsBackgroundTimeUntilItHandedTheLinkBackAndIsActiveUntilItsResult() = runBlocking {
        val board = Board()
        board.connect()
        val serial = Dispatchers.Default.limitedParallelism(1)
        val order = ArrayList<String>()
        val orderLock = NSLock()
        fun note(event: String) {
            orderLock.lock()
            order += event
            orderLock.unlock()
        }
        val keepAlive = IosKeepAlive({ _, _, unattended -> note("protect $unattended"); true }, { note("unprotect") }, serial)
        val runs = collections(board, keepAlive, serial)
        val done = CompletableDeferred<SyncResult>()
        runs.collect(device, null, LinkPreference.ANY, false, {}) {
            assertFalse(board.current!!.leased, "the link is back before the result")
            note("finished")
            done.complete(it)
        }
        assertTrue(runs.isActive(device))
        assertFalse(runs.isActive("ffffffffffff"))
        assertEquals(SyncEnd.COMPLETE, withTimeout(10_000) { done.await() }.end)
        assertEquals(listOf("protect false", "unprotect", "finished"), order)
        assertFalse(runs.isActive(device))
    }

    @Test
    fun thePickedLinkFollowsTheRunsTransport() = runBlocking {
        val board = Board()
        val wifi = board.connect(wifi = true)
        val bluetooth = board.connect(wifi = false)
        val provider = IosLinkProvider(
            { null },
            1.seconds,
            Dispatchers.Default,
            null,
            poll = 10.milliseconds,
            pick = { _, prefer -> if (prefer == LinkPreference.BLE) bluetooth else wifi },
        )
        val overBluetooth = provider.connect(device, LinkPreference.BLE)
        assertSame(bluetooth.session, overBluetooth.session)
        overBluetooth.release()
        val overWifi = provider.connect(device, LinkPreference.LAN)
        assertSame(wifi.session, overWifi.session)
        overWifi.release()
        // A Bluetooth link never serves a Wi-Fi only run.
        val wrong = IosLinkProvider({ null }, 200.milliseconds, Dispatchers.Default, null, pick = { _, _ -> bluetooth })
        assertEquals(DisconnectCause.Unreachable, assertFailsWith<LinkClosedException> { wrong.connect(device, LinkPreference.LAN) }.disconnectCause)
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.RetryPolicy
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.TimeoutPhase
import earth.walkthru.aqsync.storage.FakeStorageBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The shared controller on iOS (#81), over a scripted board that Swift reconnects to through dial. */
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

        fun connect(): IosLink {
            lateinit var link: IosLink
            link = IosLink(Port { answer(link, it) }, overWifi = true, address = device, callbacks = Dispatchers.Default, context = Dispatchers.Default)
            link.identify(station, "boot", 1024)
            current = link
            return link
        }

        /** Swift's reconnect, a fresh identified link. */
        fun dial(name: String, prefer: LinkPreference) {
            assertEquals(device, name)
            dials.incrementAndGet()
            connect()
        }

        private fun answer(link: IosLink, req: ByteArray) {
            fun reply(frame: Frame) = link.offer(FrameCodec.encode(frame).toNSData())
            when (val op = req[0].toInt() and 0xFF) {
                Opcode.STATUS -> link.offerStatus(if (anchored) "{\"utc\":1,\"clk\":1,\"clk_age_s\":60}" else "{\"utc\":0,\"clk\":0}")
                Opcode.SET_TIME -> {
                    timeSets.incrementAndGet()
                    anchored = true
                    reply(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000))
                }
                Opcode.LIST -> {
                    lists.incrementAndGet()
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

    private fun collections(board: Board) = SensorCollections(
        sync,
        IosLinkProvider({ board.current }, 5.seconds, Dispatchers.Default, board::dial, poll = 10.milliseconds),
        record,
        Dispatchers.Default,
        Dispatchers.Default,
        retry = RetryPolicy(firstBackoff = 10.milliseconds, maxBackoff = 50.milliseconds),
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
            finishes.incrementAndGet()
            done.complete(result)
        }
    }

    private fun SensorCollections.collect(seen: Seen) = collect(device, null, LinkPreference.ANY, false, seen::progress, seen::finished)

    @Test
    fun theProviderDialsWhenNoLinkIsOpenAndLendsTheNewOne() = runBlocking {
        val board = Board()
        val provider = IosLinkProvider({ board.current }, 5.seconds, Dispatchers.Default, board::dial, poll = 10.milliseconds)
        val lent = provider.connect(device, LinkPreference.ANY)
        assertEquals(1, board.dials.value)
        assertEquals(station, lent.station)
        assertTrue(board.current!!.syncRunning)
        lent.release()
        assertFalse(board.current!!.leased)

        val silent = IosLinkProvider({ null }, 100.milliseconds, Dispatchers.Default, { _, _ -> }, poll = 10.milliseconds)
        val timedOut = assertFailsWith<LinkClosedException> { silent.connect(device, LinkPreference.ANY) }
        assertEquals(DisconnectCause.Timeout(TimeoutPhase.OPEN, 0), timedOut.disconnectCause)
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
        assertEquals(1, board.dials.value)
        assertEquals(2, board.lists.value, "every attempt plans from a fresh LIST")
        assertEquals(1, board.timeSets.value)
        assertEquals(listOf(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000).epochNanos), anchors)
        assertFalse(board.current!!.leased)
        assertEquals(1, seen.finishes.value)
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
        assertEquals(2, result.files)
        assertEquals(1, result.saved)
        assertEquals(0, result.notSaved)
        assertEquals(0, board.dials.value, "an auth failure never reconnects")
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
        assertEquals(0, stopped.saved)
        assertEquals(SyncEnd.COMPLETE, done.end)
        assertEquals(2, done.saved)
        handle.cancel()
        // Late callbacks of the stopped run never reach it after its outcome.
        repeat(20) { yield() }
        assertEquals(1, first.finishes.value)
        assertEquals("finished", first.events.last())
        assertEquals(1, next.finishes.value)
        assertFalse(board.current!!.leased)
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
}

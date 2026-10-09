@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.IosArchiveStore
import earth.walkthru.aqsync.archive.ReceiptArchiveIndex
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameCodec
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.ProtocolError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Swift-facing runner and byte pipe over a scripted sensor, on the iOS archive. */
class SensorSyncTest {
    private val base = NSTemporaryDirectory().trimEnd('/') + "/sensor-sync-" + NSUUID().UUIDString()
    private val finals = "$base/SensorArchive"
    private val store = IosArchiveStore(finals, "$base/archive-partial", Dispatchers.Default)
    private val sync = SensorSync(store, ReceiptArchiveIndex(finals, Dispatchers.Default), Dispatchers.Default, Dispatchers.Default, progressInterval = 0)
    private val station = "53315f5f-cb85-4d8d-b623-d56266084189"

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

    /** Requests seen by the sensor, by opcode. */
    private val requests = ArrayList<Int>()

    /** A link whose sensor answers each request like the firmware, through [SensorLink.deliver]. */
    private fun sensor(answer: (SensorLink, ByteArray) -> Boolean = { _, _ -> false }): SensorLink {
        lateinit var link: SensorLink
        link = SensorLink(overWifi = true, address = "a1b2c3d4e5f6", mtu = 517, send = { data: NSData ->
            val req = data.toByteArray()
            requests += req[0].toInt() and 0xFF
            if (!answer(link, req)) respond(link, req)
        }, sendContext = Dispatchers.Default)
        return link
    }

    private fun SensorLink.reply(frame: Frame) = deliver(FrameCodec.encode(frame).toNSData())

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
    private var open: String? = null

    private fun respond(link: SensorLink, req: ByteArray) {
        when (val op = req[0].toInt() and 0xFF) {
            Opcode.SET_TIME -> link.reply(Frame.TimeSet(1_789_581_931L, 12_345_678L, 123_000))
            Opcode.LIST -> {
                files.forEach { (name, bytes) -> link.reply(Frame.File(bytes.size.toLong(), name)) }
                link.reply(Frame.ListEnd(files.size, 0, 0, 0))
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
            else -> link.reply(Frame.Error(op, ProtocolError.MALFORMED_REQUEST.code, ""))
        }
    }

    private fun run(link: SensorLink, progress: MutableList<SyncProgress> = ArrayList()): SyncResult = runBlocking {
        val done = CompletableDeferred<SyncResult>()
        sync.start(link, station, "a1b2c3d4e5f6", 4096, { progress += it }) { done.complete(it) }
        withTimeout(20_000) { done.await() }
    }

    @Test
    fun everyFileIsSavedThroughTheLink() {
        val progress = ArrayList<SyncProgress>()
        val result = run(sensor(), progress)

        assertEquals(SyncEnd.COMPLETE, result.end)
        assertEquals(Opcode.SET_TIME, requests.first())
        assertEquals(2, result.files)
        assertEquals(2, result.saved)
        assertTrue(result.keepsLink)
        assertEquals(2, progress.last().filesDone)
        files.forEach { (name, bytes) ->
            val saved = NSFileManager.defaultManager.contentsAtPath("$finals/$station/$name")!!.toByteArray()
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
        assertEquals(listOf(Opcode.SET_TIME, Opcode.LIST), requests)
    }

    @Test
    fun aConnectionAlreadySynchronizedDoesNotSetTimeAgain() {
        val link = sensor().also { it.markClockSynchronized() }
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        assertEquals(Opcode.LIST, requests.first())
        assertFalse(Opcode.SET_TIME in requests)
    }

    @Test
    fun theAcknowledgedAnchorKeepsTheFullStationAndBootIdentity() = runBlocking {
        data class Anchor(val station: String, val boot: String, val utc: Long, val mono: Long)
        val anchors = mutableListOf<Anchor>()
        val runner = SensorSync(
            store,
            ReceiptArchiveIndex(finals, Dispatchers.Default),
            Dispatchers.Default,
            Dispatchers.Default,
            recordClockAnchor = { st, boot, utc, mono -> anchors += Anchor(st, boot, utc, mono) }
        )
        val link = sensor().also { it.identify(station, "full-boot-identity") }
        val done = CompletableDeferred<SyncResult>()
        runner.start(link, station, "a1b2c3d4e5f6", 4096, {}) { done.complete(it) }
        assertEquals(SyncEnd.COMPLETE, withTimeout(20_000) { done.await() }.end)
        assertEquals(listOf(Anchor(station, "full-boot-identity", 1_789_581_931_123_000_000L, 12_345_678L)), anchors)
    }

    @Test
    fun refusedUtcStillPreservesOriginalArchiveFiles() {
        val link = sensor { link, req ->
            (req[0].toInt() and 0xFF == Opcode.SET_TIME).also {
                if (it) link.reply(Frame.Error(Opcode.SET_TIME, 9, "clock unavailable"))
            }
        }
        assertEquals(SyncEnd.COMPLETE, run(link).end)
        assertEquals(Opcode.SET_TIME, requests.first())
        assertTrue(Opcode.LIST in requests)
    }

    @Test
    fun aClosedLinkEndsTheSyncAsLost() {
        val result = run(sensor { link, req -> (req[0].toInt() and 0xFF == Opcode.READ).also { if (it) link.linkClosed() } })

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
    fun aStationSyncsOverOneLinkAtATime() = runBlocking {
        val first = CompletableDeferred<SyncResult>()
        val second = CompletableDeferred<SyncResult>()
        val quiet = sensor { _, _ -> true }
        val handle = sync.start(quiet, station, "a1b2c3d4e5f6", 4096, {}) { first.complete(it) }
        sync.start(sensor(), station, "a1b2c3d4e5f6", 4096, {}) { second.complete(it) }

        assertEquals(SyncEnd.IN_USE, withTimeout(5_000) { second.await() }.end)
        handle.cancel()
        assertEquals(SyncEnd.STOPPED, withTimeout(5_000) { first.await() }.end)
    }

    @Test
    fun aCancelBeforeTheWorkStartsStillFinishesOnceAndFreesTheStation() = runBlocking {
        // Work waits in this queue until the test runs it, so cancel() lands before dispatch.
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val held = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.trySend(block)
            }
        }
        val gated = SensorSync(store, ReceiptArchiveIndex(finals, Dispatchers.Default), held, Dispatchers.Default, progressInterval = 0)
        suspend fun pumpUntil(done: CompletableDeferred<*>) = withTimeout(20_000) {
            while (!done.isCompleted) {
                queue.tryReceive().getOrNull()?.run() ?: yield()
            }
        }

        val calls = AtomicInt(0)
        val first = CompletableDeferred<SyncResult>()
        gated.start(sensor(), station, "a1b2c3d4e5f6", 4096, {}) {
            calls.incrementAndGet()
            first.complete(it)
        }.cancel()
        pumpUntil(first)
        assertEquals(SyncEnd.STOPPED, first.await().end)

        val second = CompletableDeferred<SyncResult>()
        gated.start(sensor(), station, "a1b2c3d4e5f6", 4096, {}) { second.complete(it) }
        pumpUntil(second)
        assertEquals(SyncEnd.COMPLETE, second.await().end)
        assertEquals(1, calls.value)
    }

    @Test
    fun aFileThisPhoneCannotStoreFailsAloneAndKeepsTheLink() {
        val (blocked, other) = files.keys.toList()
        // A directory where the archived file belongs, so the move into the archive fails.
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$finals/$station/$blocked", true, null, null))

        val result = run(sensor())

        assertEquals(SyncEnd.STORAGE_FAILED, result.end)
        assertTrue(result.keepsLink)
        assertEquals(1, result.saved)
        assertEquals(1, result.notSaved)
        assertContentEquals(files.getValue(other), NSFileManager.defaultManager.contentsAtPath("$finals/$station/$other")!!.toByteArray())
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
}

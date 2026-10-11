package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.ble.openSession
import earth.walkthru.aqsync.data.sync.FakeArchiveDevice
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Opens a fresh [FakeArchiveDevice] per connect, as a phone opens a new link after a loss. Every
 * fake serves the same [files], so a retry sees what the last attempt left. [configure] runs on
 * each new fake with its 1-based connect number. With [keepOpen] a released link stays open, as
 * a provider that still shows the device does. With [share] a connect reuses the last link while
 * it is open, as a provider with one link per device does, and [maxLent] shows two lends at once.
 */
class FakeLinks(
    private val scope: CoroutineScope,
    val station: String = "00000000-0000-4000-8000-000000000001",
    private val kind: TransportKind = TransportKind.FAKE,
    private val keepOpen: Boolean = false,
    private val share: Boolean = false,
    private val configure: FakeArchiveDevice.(connect: Int) -> Unit = {},
) : LinkProvider {
    private val template = FakeArchiveDevice(scope, station = station)
    val files: MutableList<FakeArchiveDevice.FakeFile> = template.files

    /** Every fake opened, in order. */
    val devices: MutableList<FakeArchiveDevice> = mutableListOf()
    val links: MutableList<CollectionLink> = mutableListOf()
    val preferences: MutableList<LinkPreference> = mutableListOf()
    var connects: Int = 0
        private set
    var releases: Int = 0
        private set

    /** Links lent and not yet released, and the most at once. */
    var lent: Int = 0
        private set
    var maxLent: Int = 0
        private set

    /** A cause to refuse the next connects with, as an unreachable or rejecting device does. */
    var refuse: ((connect: Int) -> DisconnectCause?) = { null }

    /** Makes every release throw after it handed the link back, as a faulty adapter might. */
    var failRelease: Boolean = false

    /** How long a connect takes after it lent the link, as a slow handshake does. */
    var connectDelayMs: (connect: Int) -> Long = { 0 }

    /** Makes that handshake ignore cancellation, so a stopped run keeps its lend for a while. */
    var uninterruptible: Boolean = false

    private var last: Pair<FakeArchiveDevice, DeviceSession>? = null

    override suspend fun connect(device: String, prefer: LinkPreference): CollectionLink {
        preferences += prefer
        val n = ++connects
        refuse(n)?.let { throw LinkClosedException(it) }
        val (fake, session) = last?.takeIf { share && it.second.link.state.value is LinkState.Ready } ?: run {
            val fake = FakeArchiveDevice(scope, address = device, station = station, kind = kind)
            fake.files.clear()
            fake.files += files
            fake.configure(n)
            devices += fake
            (fake to openSession(fake, scope)).also { last = it }
        }
        val link = CollectionLink(session, station, fake.maxRead) {
            releases++
            lent--
            if (!keepOpen) session.link.close("released")
            check(!failRelease) { "release failed" }
        }
        links += link
        maxLent = maxOf(maxLent, ++lent)
        // The LinkProvider contract. A connect cancelled after its lend hands that lend back.
        try {
            val wait = connectDelayMs(n)
            if (uninterruptible) withContext(NonCancellable) { delay(wait) } else delay(wait)
        } catch (e: CancellationException) {
            link.release()
            throw e
        }
        return link
    }
}

/** Counts every lease and every close of it, so a test sees a lease that closes twice. */
class CountingKeepAlive(
    private val refuse: Set<KeepAliveKind> = emptySet(),
    /** What the state was at each acquire and close, set by the test once the controller exists. */
    var observe: () -> CollectionState = { CollectionState.Idle },
) : KeepAlive {
    inner class Counted(val kind: KeepAliveKind) : Lease {
        var closes = 0
            private set

        override fun close() {
            closes++
            log += "close ${kind.name} in ${observe()::class.simpleName}"
        }
    }

    val leases: MutableList<Counted> = mutableListOf()
    val log: MutableList<String> = mutableListOf()

    override fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease? {
        log += "acquire ${kind.name} in ${observe()::class.simpleName}"
        if (kind in refuse) return null
        return Counted(kind).also { leases += it }
    }

    fun acquired(kind: KeepAliveKind): Int = leases.count { it.kind == kind }

    fun held(kind: KeepAliveKind): Int = leases.count { it.kind == kind && it.closes == 0 }

    /** True when every lease taken was closed exactly once. */
    fun allClosedOnce(): Boolean = leases.all { it.closes == 1 }
}

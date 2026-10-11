package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.ble.openSession
import earth.walkthru.aqsync.data.sync.FakeArchiveDevice
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.LinkClosedException
import kotlinx.coroutines.CoroutineScope

/**
 * Opens a fresh [FakeArchiveDevice] per connect, as a phone opens a new link after a loss. Every
 * fake serves the same [files], so a retry sees what the last attempt left. [configure] runs on
 * each new fake with its 1-based connect number. With [keepOpen] a released link stays open, as
 * a provider that still shows the device does.
 */
class FakeLinks(
    private val scope: CoroutineScope,
    val station: String = "00000000-0000-4000-8000-000000000001",
    private val kind: TransportKind = TransportKind.FAKE,
    private val keepOpen: Boolean = false,
    private val configure: FakeArchiveDevice.(connect: Int) -> Unit = {},
) : LinkProvider {
    private val template = FakeArchiveDevice(scope, station = station)
    val files: MutableList<FakeArchiveDevice.FakeFile> = template.files

    /** Every fake opened, in order. */
    val devices: MutableList<FakeArchiveDevice> = mutableListOf()
    val links: MutableList<CollectionLink> = mutableListOf()
    var connects: Int = 0
        private set
    var releases: Int = 0
        private set

    /** A cause to refuse the next connects with, as an unreachable or rejecting device does. */
    var refuse: ((connect: Int) -> DisconnectCause?) = { null }

    override suspend fun connect(device: String, prefer: LinkPreference): CollectionLink {
        val n = ++connects
        refuse(n)?.let { throw LinkClosedException(it) }
        val fake = FakeArchiveDevice(scope, address = device, station = station, kind = kind)
        fake.files.clear()
        fake.files += files
        fake.configure(n)
        devices += fake
        val session = openSession(fake, scope)
        return CollectionLink(session, station, fake.maxRead) {
            releases++
            if (!keepOpen) session.link.close("released")
        }.also { links += it }
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

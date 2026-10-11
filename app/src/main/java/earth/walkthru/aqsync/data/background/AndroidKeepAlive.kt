package earth.walkthru.aqsync.data.background

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.collection.CollectionIntent
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.collection.Lease
import earth.walkthru.aqsync.data.lan.LanTransferLeases
import earth.walkthru.aqsync.data.lan.WifiLeases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android [KeepAlive]. PROCESS holds [SyncForegroundService] up while any lease is open, WIFI
 * takes the LAN Wi-Fi lock. The first PROCESS lease starts the service synchronously, so a tap
 * must call [acquire] while the app is in the foreground. A refused start returns null.
 */
@Singleton
class AndroidKeepAlive internal constructor(
    private val startProcess: () -> Boolean,
    private val wifi: WifiLeases,
) : KeepAlive {

    @Inject
    constructor(@ApplicationContext context: Context, wifi: WifiLeases) :
        this({ SyncForegroundService.start(context) }, wifi)

    private val held = MutableStateFlow(false)

    /** True while a PROCESS lease is open. [SyncForegroundService] stays up while it is. */
    val processHeld: StateFlow<Boolean> = held.asStateFlow()

    private val process = LanTransferLeases(
        acquireResource = {
            held.value = true
            startProcess().also { if (!it) held.value = false }
        },
        releaseResource = { held.value = false },
    )

    override fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease? {
        val lease = when (kind) {
            KeepAliveKind.PROCESS -> process.acquire()
            KeepAliveKind.WIFI -> wifi.acquireLease()
        } ?: return null
        val closed = AtomicBoolean(false)
        return Lease { if (closed.compareAndSet(false, true)) runCatching { lease.close() } }
    }
}

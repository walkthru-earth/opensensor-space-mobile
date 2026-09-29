package earth.walkthru.aqsync.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.protocol.LanFraming
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/** One `_aqsync._tcp` instance on the current network, resolved. */
data class LanDevice(
    /** DNS-SD instance name = BLE local name, `AQ-xxxx`. */
    val serviceName: String,
    val host: InetAddress?,
    val port: Int,
    /** Parsed TXT records. */
    val txt: Map<String, String>,
    val lastSeenEpochMs: Long,
) {
    val station: String? get() = txt[LanFraming.TXT_STATION]
    val dev: String? get() = txt[LanFraming.TXT_DEV]
    val fw: String? get() = txt[LanFraming.TXT_FW]
    val proto: Int? get() = txt[LanFraming.TXT_PROTO]?.toIntOrNull()

    /** Usable as a connect target: resolved address, sane port, and a station to match a known device. */
    val isConnectable: Boolean get() = host != null && port in 1..65535 && station != null
}

/**
 * mDNS/DNS-SD browse for `_aqsync._tcp` with [NsdManager]. Emits the full map of currently
 * visible, resolved devices keyed by instance name whenever it changes.
 *
 * On API 34+ each found service gets a [NsdManager.ServiceInfoCallback] (continuous updates,
 * addresses + TXT). On API 31–33 the deprecated one-shot [NsdManager.resolveService] is used,
 * serialized because the framework rejects concurrent resolves with `FAILURE_ALREADY_ACTIVE`.
 *
 * Caller must hold `ACCESS_LOCAL_NETWORK` on API 37+ (see `ui/permissions/LanPermission.kt`);
 * without it the framework blocks all local-network traffic including mDNS and the flow completes
 * with an error message.
 */
@Singleton
class LanDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface Event {
        data class Devices(val devices: Map<String, LanDevice>) : Event
        data class Failed(val message: String) : Event
    }

    private var sharedResolver: LegacyResolver? = null

    fun discover(): Flow<Event> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            trySend(Event.Failed("this device has no NSD service"))
            close()
            return@callbackFlow
        }
        val devices = LinkedHashMap<String, LanDevice>()
        val lock = Any()
        val executor = Executor { it.run() }

        fun publish() {
            val snapshot = synchronized(lock) { devices.toMap() }
            trySend(Event.Devices(snapshot))
        }

        fun upsert(info: NsdServiceInfo) {
            val dev = info.toLanDevice()
            synchronized(lock) { devices[dev.serviceName] = dev }
            publish()
        }

        fun remove(name: String) {
            val removed = synchronized(lock) { devices.remove(name) != null }
            if (removed) publish()
        }

        val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
        val resolver = synchronized(this@LanDiscovery) { sharedResolver ?: LegacyResolver(nsd).also { sharedResolver = it } }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "browsing $serviceType")
                publish()
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(LanFraming.SERVICE_TYPE)) return
                if (Build.VERSION.SDK_INT >= 34) {
                    val cb = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(TAG, "info callback failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceUpdated(updated: NsdServiceInfo) = upsert(updated)
                        override fun onServiceLost() = remove(info.serviceName)
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    synchronized(infoCallbacks) {
                        infoCallbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                        infoCallbacks[info.serviceName] = cb
                    }
                    try {
                        nsd.registerServiceInfoCallback(info, executor, cb)
                    } catch (e: Exception) {
                        Log.w(TAG, "registerServiceInfoCallback: ${e.message}")
                    }
                } else {
                    resolver.enqueue(info) { upsert(it) }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    synchronized(infoCallbacks) { infoCallbacks.remove(info.serviceName) }
                        ?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                }
                remove(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(Event.Failed("mDNS browse could not start (NSD error $errorCode)"))
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsd.discoverServices(LanFraming.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: SecurityException) {
            trySend(Event.Failed("local network access is not permitted: ${e.message}"))
            close()
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            trySend(Event.Failed("mDNS browse rejected: ${e.message}"))
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (Build.VERSION.SDK_INT >= 34) {
                synchronized(infoCallbacks) {
                    infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                    infoCallbacks.clear()
                }
            }
        }
    }

    /** API 31–33: one `resolveService` at a time. */
    private class LegacyResolver(private val nsd: NsdManager) {
        private val queue = ArrayDeque<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()
        private var busy = false

        fun enqueue(info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
            synchronized(this) {
                queue.addLast(info to onResolved)
                if (busy) return
                busy = true
            }
            next()
        }

        private fun next() {
            val request = synchronized(this) {
                queue.removeFirstOrNull() ?: run { busy = false; null }
            } ?: return
            val (info, onResolved) = request
            @Suppress("DEPRECATION")
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        next()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        onResolved(serviceInfo)
                        next()
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "LanDiscovery"

        internal fun NsdServiceInfo.toLanDevice(): LanDevice {
            val txt = attributes.orEmpty().mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
            val addr: InetAddress? = if (Build.VERSION.SDK_INT >= 34) {
                // Prefer IPv4: the device's lwIP stack advertises A records first and link-local v6 needs a scope.
                hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: hostAddresses.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                host
            }
            return LanDevice(
                serviceName = serviceName,
                host = addr,
                port = port,
                txt = txt,
                lastSeenEpochMs = System.currentTimeMillis(),
            )
        }
    }
}

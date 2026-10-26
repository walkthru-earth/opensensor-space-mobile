package space.opensensor.data.background

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.opensensor.collection.CollectionIntent
import space.opensensor.collection.CollectionOrigin
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.Lease
import space.opensensor.data.lan.WifiLeases
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Interactive PROCESS leases own a generation of the app service. Workers supply their own. */
@Singleton
class AndroidKeepAlive internal constructor(
    private val startGeneration: (Long) -> Boolean,
    private val wifi: WifiLeases,
) : KeepAlive {

    @Inject
    constructor(@ApplicationContext context: Context, wifi: WifiLeases) :
        this({ generation: Long -> SyncForegroundService.start(context, generation) }, wifi)

    enum class ServiceLoss { REFUSED, TIMEOUT, DESTROYED }

    private val lock = ReentrantLock()
    private val held = MutableStateFlow(false)
    val processHeld: StateFlow<Boolean> = held.asStateFlow()
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private val owners = mutableMapOf<Any, Owner>()
    val generation: Long? get() = lock.withLock { activeGeneration }

    /** Takes the lease before Android start, then invokes OS code outside the accounting lock. */
    fun acquireProcess(intent: CollectionIntent, onLost: (ServiceLoss) -> Unit): Lease? {
        if (intent.origin != CollectionOrigin.USER_TAP) return null
        val key = Any()
        val (generation, mustStart) = lock.withLock {
            val start = activeGeneration == null
            val generation = activeGeneration ?: (++nextGeneration).also { activeGeneration = it }
            owners[key] = Owner(generation, onLost)
            held.value = true
            generation to start
        }
        if (mustStart && !startGeneration(generation)) {
            onServiceStopped(generation, ServiceLoss.REFUSED)
            return null
        }
        val alive = lock.withLock { owners.containsKey(key) }
        if (!alive) return null
        val closed = AtomicBoolean(false)
        return Lease {
            if (closed.compareAndSet(false, true)) {
                lock.withLock {
                    owners.remove(key)
                    held.value = owners.isNotEmpty()
                }
            }
        }
    }

    /** Ignore stale callbacks. Notify just that generation's requests, always outside the lock. */
    fun onServiceStopped(generation: Long, loss: ServiceLoss = ServiceLoss.DESTROYED) {
        val callbacks = lock.withLock {
            if (activeGeneration != generation) return
            activeGeneration = null
            val affected = owners.filterValues { it.generation == generation }
            affected.keys.forEach(owners::remove)
            held.value = owners.isNotEmpty()
            affected.values.map { it.onLost }
        }
        callbacks.forEach { runCatching { it(loss) } }
    }

    /** A lease-only idle check and service shutdown claim share the same generation lock. */
    fun finishIfIdle(generation: Long): Boolean = lock.withLock {
        if (activeGeneration != generation || owners.isNotEmpty()) {
            false
        } else {
            activeGeneration = null
            true
        }
    }

    override fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease? = when (kind) {
        KeepAliveKind.PROCESS -> acquireProcess(intent) {}
        KeepAliveKind.WIFI -> wifi.acquireLease()?.let { lease ->
            val closed = AtomicBoolean(false)
            Lease { if (closed.compareAndSet(false, true)) runCatching { lease.close() } }
        }
    }

    private class Owner(val generation: Long, val onLost: (ServiceLoss) -> Unit)
}

package earth.walkthru.aqsync.data

import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/** Full hardware IDs prevent two endpoint aliases from opening competing files on one board. */
@Singleton
class DeviceIdentityClaims @Inject constructor() {
    private val owners = mutableMapOf<String, Any>()

    @Synchronized
    fun claim(deviceId: String, owner: Any): AutoCloseable? {
        val key = deviceId.lowercase()
        if (owners.containsKey(key)) return null
        val claim = owner to Any()
        owners[key] = claim
        return AutoCloseable { synchronized(this) { if (owners[key] === claim) owners.remove(key) } }
    }
}

/** Shared across interactive and worker transfers. Two is a conservative policy, not a BLE limit. */
@Singleton
class CollectionBudget @Inject constructor() {
    private val permits = Semaphore(2)
    suspend fun acquire() = permits.acquire()
    fun release() = permits.release()
}

package earth.walkthru.aqsync.data.lan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Shared foreground discovery; controllers consume its targets without each starting a browse. */
@Singleton
class LanDiscoveryState @Inject constructor(private val discovery: LanDiscovery) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _devices = MutableStateFlow<Map<String, LanDevice>>(emptyMap())
    val devices = _devices.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private val browseMutex = Mutex()

    @Synchronized
    fun setActive(active: Boolean) {
        // Permission or lifecycle recomposition can ask for the same active state again. Keep
        // the current NSD browse instead of tearing it down and losing its resolved targets.
        if (active && job?.isActive == true) return
        generation++
        val run = generation
        job?.cancel()
        job = null
        _devices.value = emptyMap()
        if (!active) return
        _error.value = null
        job = scope.launch {
            try {
                // A rapid stop/start must wait until the previous callbackFlow has unregistered
                // its NSD listener before a new browse asks Android for the same service.
                browseMutex.withLock {
                    discovery.discover().collect { event ->
                        synchronized(this@LanDiscoveryState) {
                            if (run == generation) {
                                when (event) {
                                    is LanDiscovery.Event.Devices -> _devices.value = event.devices.values.filter { it.isConnectable }.associateBy { it.station!! }
                                    is LanDiscovery.Event.Failed -> {
                                        _devices.value = emptyMap()
                                        _error.value = event.message
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(this@LanDiscoveryState) {
                    if (run == generation) {
                        _devices.value = emptyMap()
                        _error.value = e.message ?: "mDNS browse failed"
                    }
                }
            }
        }
    }
}

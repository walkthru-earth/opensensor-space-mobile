package earth.walkthru.aqsync.data.lan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    @Synchronized
    fun setActive(active: Boolean) {
        job?.cancel()
        job = null
        _devices.value = emptyMap()
        if (!active) return
        _error.value = null
        job = scope.launch {
            try {
                discovery.discover().collect { event ->
                    when (event) {
                        is LanDiscovery.Event.Devices -> _devices.value = event.devices.values.filter { it.isConnectable }.associateBy { it.station!! }
                        is LanDiscovery.Event.Failed -> _error.value = event.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "mDNS browse failed"
            }
        }
    }
}

package earth.walkthru.opensensor

import earth.walkthru.opensensor.debug.DebugSyncStep
import earth.walkthru.opensensor.debug.DebugSyncTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Small, secret-free trace of the foreground sync path for the shared Debug screen. */
internal object IosSyncDiagnostics {
    private val traces = MutableStateFlow<Map<String, DebugSyncTrace>>(emptyMap())

    fun forDevice(device: String) = traces.map { it[device.lowercase()] }

    fun record(device: String?, step: String, detail: String) {
        if (device.isNullOrBlank()) return
        val key = device.lowercase()
        val old = traces.value[key] ?: DebugSyncTrace()
        val next = old.steps.takeLast(31) + DebugSyncStep(Clock.System.now().toEpochMilliseconds(), step, detail)
        traces.value = traces.value + (key to old.copy(steps = next))
    }
}

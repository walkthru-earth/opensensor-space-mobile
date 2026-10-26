package space.opensensor.ui.scan

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import space.opensensor.data.ble.BleScanner
import space.opensensor.data.ble.ScannedDevice

/**
 * Advertisements handled at display rate. A scan delivers every advert, so they land in a map owned
 * by [collect], [seen] publishes it at most once per [publishMs], and [touch] (a Room UPDATE that
 * makes the known device list re-emit) runs at most once per address per [touchMs].
 */
internal class NearbyAdverts(
    private val touch: suspend (ScannedDevice) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val publishMs: Long = 250,
    private val touchMs: Long = 60_000,
    private val staleMs: Long = 15_000,
) {
    private val published = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    val seen: StateFlow<Map<String, ScannedDevice>> = published.asStateFlow()

    /** Kept across sessions, so Scan again within a minute does not write Room again. */
    private val lastTouchMs = HashMap<String, Long>()

    private sealed interface Input {
        data class Advert(val event: BleScanner.Event) : Input
        data object Tick : Input
        data object End : Input
    }

    /** Collects one scan session until it ends or fails. Returns the failure, if any. */
    suspend fun collect(events: Flow<BleScanner.Event>): BleScanner.Event.Failed? {
        val latest = HashMap(published.value)
        var dirty = false
        var failure: BleScanner.Event.Failed? = null
        val ticks = flow {
            while (true) {
                delay(publishMs)
                emit(Input.Tick)
            }
        }
        merge(events.map<BleScanner.Event, Input> { Input.Advert(it) }.onCompletion { if (it == null) emit(Input.End) }, ticks)
            .takeWhile { input ->
                if (input is Input.Advert && input.event is BleScanner.Event.Failed) failure = input.event
                input != Input.End && failure == null
            }
            .onEach { input ->
                when (input) {
                    is Input.Advert -> {
                        val device = (input.event as BleScanner.Event.Seen).device
                        latest[device.address] = device
                        dirty = true
                        val now = nowMs()
                        val last = lastTouchMs[device.address]
                        if (last == null || now - last >= touchMs) {
                            lastTouchMs[device.address] = now
                            // Only an explicit connection enrolls a device. A passive scan must
                            // not recreate a row that the user chose to forget.
                            touch(device)
                        }
                    }
                    Input.Tick -> {
                        val cutoff = nowMs() - staleMs
                        if (latest.values.removeAll { it.lastSeenEpochMs < cutoff }) dirty = true
                        if (dirty) {
                            published.value = latest.toMap()
                            dirty = false
                        }
                    }
                    Input.End -> Unit
                }
            }
            .collect()
        if (dirty) published.value = latest.toMap()
        return failure
    }
}

package space.opensensor.data.ble

import space.opensensor.sync.SyncLock

/**
 * One [FrameRecorder] per device address, kept across short-lived sessions so the Debug screen can
 * show the frames of the last sync and of earlier raw commands. At most [maxDevices] recorders are
 * kept; the least recently used one is dropped first. iOS uses this because each sync and each raw
 * command opens its own link, while Android keeps one session per connection.
 */
class FrameRecorderRegistry(
    private val maxDevices: Int = DEFAULT_MAX_DEVICES,
    private val factory: () -> FrameRecorder = { FrameRecorder() },
) {
    private val lock = SyncLock()
    private val recorders = LinkedHashMap<String, FrameRecorder>()

    init {
        require(maxDevices > 0)
    }

    fun recorderFor(address: String): FrameRecorder = lock.withLock {
        val existing = recorders.remove(address)
        val recorder = existing ?: factory()
        recorders[address] = recorder
        while (recorders.size > maxDevices) recorders.remove(recorders.keys.first())
        recorder
    }

    val size: Int get() = lock.withLock { recorders.size }

    companion object {
        const val DEFAULT_MAX_DEVICES = 8
    }
}

package space.opensensor

/**
 * Counts the open pages that hold each Bluetooth sensor, Today and its Device page. Popping the last
 * page for a sensor calls [close] once with its id, so Today, then Device, then Back keeps the link
 * while Today stays open. Swift closes the link only when it is idle. Main thread only.
 */
class PeripheralLeases(private val close: (String) -> Unit) {
    private val holders = mutableMapOf<String, Int>()

    /** A page for [id] opened. */
    fun acquire(id: String) {
        val key = id.uppercase()
        holders[key] = (holders[key] ?: 0) + 1
    }

    /** A page for [id] left the back stack. The last one offers the link back. */
    fun release(id: String) {
        val key = id.uppercase()
        val left = (holders[key] ?: return) - 1
        if (left > 0) {
            holders[key] = left
        } else {
            holders.remove(key)
            close(id)
        }
    }
}

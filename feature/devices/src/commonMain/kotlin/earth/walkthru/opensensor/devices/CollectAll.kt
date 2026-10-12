package earth.walkthru.opensensor.devices

/** How one Collect all step reaches its sensor. */
enum class CollectRoute { WIFI, BLUETOOTH }

/** One sensor to collect from. [wifiName] is the Wi-Fi service name when [route] is [CollectRoute.WIFI]. */
data class CollectStep(
    val id: String,
    val name: String?,
    val station: String?,
    val route: CollectRoute,
    val wifiName: String? = null,
)

/**
 * Which known sensors Collect all visits, and how. Pure. Android plans in its background scheduler,
 * iOS runs this plan through [CollectAllQueue].
 *
 * - A sensor with an open link is skipped. Its own screen already owns it.
 * - One step per station, so a station never syncs over two links at once.
 * - Wi-Fi when this phone holds the token and the station is on the Wi-Fi now, otherwise Bluetooth.
 * - Wi-Fi steps go first. They are faster and free the Bluetooth radio for the rest.
 */
object CollectAllPlan {
    fun plan(known: List<KnownSensor>, wifi: List<WifiSensor>, activeIds: Set<String>): List<CollectStep> {
        val wifiByStation = wifi.filter { it.station != null }.associateBy { it.station }
        val seenStations = HashSet<String>()
        val steps = known.distinctBy { it.id }.filter { it.id !in activeIds }.mapNotNull { k ->
            val station = k.station
            if (station != null && !seenStations.add(station)) return@mapNotNull null
            val lan = station?.let(wifiByStation::get)
            if (k.hasWifiAccess && lan != null) {
                CollectStep(k.id, k.name, station, CollectRoute.WIFI, lan.name)
            } else {
                CollectStep(k.id, k.name, station, CollectRoute.BLUETOOTH)
            }
        }
        return steps.sortedBy { it.route.ordinal }
    }
}

/**
 * Runs a [CollectAllPlan] at most [parallel] steps at a time. Not thread safe, drive it from one
 * thread (the iOS main thread). [start] and [finished] return the steps the caller should begin now.
 */
class CollectAllQueue(steps: List<CollectStep>, private val parallel: Int = 1) {
    init {
        require(parallel >= 1) { "parallel must be at least 1" }
    }

    private val pending = ArrayDeque(steps)
    private val running = LinkedHashMap<String, CollectStep>()

    val total: Int = steps.size
    var succeeded: Int = 0
        private set
    var failed: Int = 0
        private set

    /** True until every step finished or the queue was cancelled and the running steps ended. */
    val active: Boolean get() = pending.isNotEmpty() || running.isNotEmpty()

    /** The ids of the steps running now. */
    val runningIds: Set<String> get() = running.keys.toSet()

    /** The steps running now, with their route. */
    val runningSteps: List<CollectStep> get() = running.values.toList()

    /** The ids of the steps not started yet. */
    val plannedIds: Set<String> get() = pending.mapTo(LinkedHashSet()) { it.id }

    fun start(): List<CollectStep> = fill()

    /** Marks [id] done and returns the next steps to begin. An unknown id changes nothing. */
    fun finished(id: String, ok: Boolean): List<CollectStep> {
        if (running.remove(id) == null) return emptyList()
        if (ok) succeeded++ else failed++
        return fill()
    }

    /** Drops the steps that have not started. Running steps still report [finished]. */
    fun cancel() {
        pending.clear()
    }

    private fun fill(): List<CollectStep> {
        val started = ArrayList<CollectStep>()
        while (running.size < parallel && pending.isNotEmpty()) {
            val step = pending.removeFirst()
            val busyStation = step.station != null && running.values.any { it.station == step.station }
            if (busyStation) continue
            running[step.id] = step
            started += step
        }
        return started
    }
}

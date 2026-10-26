package space.opensensor.data.background

/**
 * Command ownership for one service instance. Android start IDs identify commands; the PROCESS
 * lease generation identifies the collection lifetime. A later command in the same generation
 * must not invalidate a timeout Android already queued. Used on the service's main thread only.
 */
internal class SyncServiceOwnership {
    enum class Decision { ACCEPT_START, STOP_COLLECTIONS, STOP_IDLE, IGNORE }

    var generation: Long = -1L
        private set
    var lastStartId: Int = 0
        private set
    var generationStartId: Int = Int.MAX_VALUE
        private set

    /** Every command advances stopSelf's ID; only adoption of a new generation advances timeout's. */
    fun command(
        startId: Int,
        requestedGeneration: Long?,
        activeGeneration: Long?,
        isStop: Boolean,
        inForeground: Boolean,
    ): Decision {
        lastStartId = startId
        if (isStop) {
            // A retained Stop can create a fresh service before the queued start arrives.
            if (generation < 0 && activeGeneration != null) adopt(activeGeneration, startId)
            return Decision.STOP_COLLECTIONS
        }
        if (requestedGeneration == null || requestedGeneration < 0 || requestedGeneration != activeGeneration) {
            return if (isUnleasedIdle(inForeground, activeGeneration)) Decision.STOP_IDLE else Decision.IGNORE
        }
        if (generation != requestedGeneration) adopt(requestedGeneration, startId)
        return Decision.ACCEPT_START
    }

    /** Old generations cannot time out a restart, while later Stop/stale commands preserve it. */
    fun acceptsTimeout(startId: Int): Boolean = generation >= 0 && startId >= generationStartId

    /** A recreated idle service has no lease generation to claim through AndroidKeepAlive. */
    fun isUnleasedIdle(inForeground: Boolean, activeGeneration: Long?): Boolean = !inForeground && activeGeneration == null

    private fun adopt(next: Long, startId: Int) {
        generation = next
        generationStartId = startId
    }
}

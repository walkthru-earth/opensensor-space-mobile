package earth.walkthru.aqsync.data

import java.util.concurrent.atomic.AtomicLong

/** Orders explicit connection actions before their asynchronous work reaches the controller mutex. */
internal class ConnectionIntent {
    private val current = AtomicLong()

    fun claim(): Long = current.incrementAndGet()

    fun owns(claim: Long): Boolean = current.get() == claim
}

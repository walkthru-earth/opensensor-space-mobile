package space.opensensor.data

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** Orders explicit connection actions before their asynchronous work reaches the controller mutex. */
@OptIn(ExperimentalAtomicApi::class)
class ConnectionIntent {
    private val current = AtomicLong(0L)

    fun claim(): Long = current.incrementAndFetch()

    fun owns(claim: Long): Boolean = current.load() == claim
}

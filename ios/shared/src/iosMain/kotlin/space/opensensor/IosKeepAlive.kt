package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import space.opensensor.collection.CollectionIntent
import space.opensensor.collection.CollectionOrigin
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.Lease
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * The iOS protection leases (#81). A run's process lease is Swift's background time: a UIKit
 * background task for a run the user started, or the BGTask already running for an unattended one.
 * The controller takes it in `start`, on the main thread, so [protect] runs there. A refusal fails
 * the run before it touches the link. Closing the lease calls [unprotect] on [main], queued before
 * the run's `finished`. iOS has no Wi-Fi lock, so that lease holds nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class IosKeepAlive(
    private val protect: (String, Long, Boolean) -> Boolean,
    private val unprotect: (Long) -> Unit,
    main: CoroutineContext,
) : KeepAlive {
    private val scope = CoroutineScope(SupervisorJob() + main)

    override fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease? {
        if (kind != KeepAliveKind.PROCESS) return Lease {}
        if (!protect(intent.device, intent.id, intent.origin == CollectionOrigin.WORKER)) return null
        val closed = AtomicBoolean(false)
        return Lease { if (closed.compareAndSet(false, true)) scope.launch { unprotect(intent.id) } }
    }
}

package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import space.opensensor.collection.CollectionIntent
import space.opensensor.collection.CollectionOrigin
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.Lease
import space.opensensor.model.ThingId
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/**
 * The iOS protection leases (#81). A run's process lease is Swift's background time: a UIKit
 * background task for a run the user started, or the BGTask already running for an unattended one.
 * The controller takes it in `start`, on the main thread, so [protect] runs there. A refusal fails
 * the run before it touches the link. Closing the lease calls [unprotect] on [main], queued before
 * the run's `finished`. iOS has no Wi-Fi lock, so that lease holds nothing. Only a board's run
 * takes one, so any other Thing is refused.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class IosKeepAlive(
    private val protect: (ThingId.BleAddress, Long, Boolean) -> Boolean,
    private val unprotect: (Long) -> Unit,
    main: CoroutineContext,
) : KeepAlive {
    private val scope = CoroutineScope(SupervisorJob() + main)

    override fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease? {
        if (kind != KeepAliveKind.PROCESS) return Lease {}
        val board = intent.device as? ThingId.BleAddress ?: return null
        if (!protect(board, intent.id, intent.origin == CollectionOrigin.WORKER)) return null
        val closed = AtomicBoolean(false)
        return Lease { if (closed.compareAndSet(false, true)) scope.launch { unprotect(intent.id) } }
    }
}

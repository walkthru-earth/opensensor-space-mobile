package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.CollectionOrigin
import earth.walkthru.aqsync.collection.Lease
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Atomic process-wide admission. Worker jobs and interactive collections never overlap. */
@Singleton
class CollectionAdmission @Inject constructor() {
    private val lock = ReentrantLock()
    private var interactive = 0
    private var workers = 0

    val hasInteractive: Boolean get() = lock.withLock { interactive > 0 }

    /** True while any collection, interactive or worker, holds a lease. Settings refuses to remove files then. */
    val busy: Boolean get() = lock.withLock { interactive > 0 || workers > 0 }

    fun acquire(origin: CollectionOrigin): Lease? = lock.withLock {
        if ((origin == CollectionOrigin.WORKER && interactive > 0) || (origin == CollectionOrigin.USER_TAP && workers > 0)) return null
        if (origin == CollectionOrigin.USER_TAP) interactive++ else workers++
        val closed = AtomicBoolean(false)
        Lease {
            if (closed.compareAndSet(false, true)) {
                lock.withLock {
                    if (origin == CollectionOrigin.USER_TAP) interactive-- else workers--
                }
            }
        }
    }
}

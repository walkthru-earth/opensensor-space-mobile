package earth.walkthru.aqsync.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The process's memory pressure signal (#61). Android feeds it from `ComponentCallbacks2.onTrimMemory`
 * and iOS from `UIApplicationDidReceiveMemoryWarningNotification`. Rebuildable memory caches
 * ([MemoryTrimmable]) collect [levels] and give memory back.
 */
object MemoryPressure {
    enum class Level {
        /** The UI went to the background. Trim to half. */
        Moderate,

        /** The OS is about to kill processes. Drop everything that can be rebuilt. */
        Critical,
    }

    private val flow = MutableSharedFlow<Level>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every signal from now on. No replay: an old warning says nothing about memory now. */
    val levels: SharedFlow<Level> = flow.asSharedFlow()

    /** Called by the platform hook. Never blocks and never fails. */
    fun signal(level: Level) {
        flow.tryEmit(level)
    }
}

/** A memory cache that can shrink on [MemoryPressure]. */
fun interface MemoryTrimmable {
    /** Moderate trims to half the budget, Critical clears. */
    suspend fun trim(level: MemoryPressure.Level)
}

/** Trims [caches] on every [MemoryPressure] signal while this scope lives. */
fun CoroutineScope.trimOnMemoryPressure(vararg caches: MemoryTrimmable, levels: SharedFlow<MemoryPressure.Level> = MemoryPressure.levels): Job = launch {
    levels.collect { level -> for (cache in caches) cache.trim(level) }
}

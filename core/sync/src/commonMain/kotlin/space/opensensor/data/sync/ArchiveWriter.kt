package space.opensensor.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import space.opensensor.archive.PartialWriter
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Writes one file's chunks to its partial on [io], off the coroutine that reads frames. The link
 * keeps reading while a write runs, and a slow disk holds at most [capacity] chunks here before
 * [write] suspends. A suspended [write] holds the `READ` reply, so the next window is not asked
 * for until the writer took the last one. The window is the credit.
 *
 * The writer owns [partial] from here on and closes it when its coroutine ends, in [scope]. A
 * write failure is kept, the rest of the queue is discarded, and the next [write] or [finish]
 * throws it as a [StorageFault]. [written] is where the bytes on disk end, always contiguous.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ArchiveWriter(
    private val partial: PartialWriter,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    capacity: Int = CAPACITY,
) {
    private class Pending(val offset: Long, val bytes: ByteArray)

    private val queue = Channel<Pending>(capacity)
    private val fault = AtomicReference<Exception?>(null)
    private val end = AtomicLong(partial.resumeFrom)

    // ATOMIC, so the partial is closed even when the run is cancelled before the writer starts.
    private val job = scope.launch(io.limitedParallelism(1), CoroutineStart.ATOMIC) {
        try {
            for (p in queue) {
                // A queued chunk is taken without suspending, so a cancelled run is checked here.
                ensureActive()
                if (fault.load() != null) continue
                try {
                    partial.writeAt(p.offset, p.bytes)
                    end.store(p.offset + p.bytes.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fault.store(e)
                }
            }
            if (fault.load() == null) {
                try {
                    partial.sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fault.store(e)
                }
            }
        } finally {
            try {
                partial.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fault.compareAndSet(null, e)
            }
        }
    }

    /** Where the bytes on disk end. */
    val written: Long get() = end.load()

    /** Queue [bytes] for [offset], which continues the previous write. Suspends while the queue is full. */
    suspend fun write(offset: Long, bytes: ByteArray) {
        fault.load()?.let { throw StorageFault(it) }
        queue.send(Pending(offset, bytes))
    }

    /** Write what is queued, sync and close. Throws [StorageFault] when any of it failed. */
    suspend fun finish(): Long {
        queue.close()
        job.join()
        fault.load()?.let { throw StorageFault(it) }
        return written
    }

    /**
     * Stop taking chunks. What is queued is still written unless [scope] is cancelled too, which
     * drops the queue. Either way the partial is closed and resume reads its length from disk.
     */
    fun abandon() {
        queue.close()
    }

    companion object {
        /** Chunks queued between the reader and the disk. */
        const val CAPACITY = 2
    }
}

/** This phone's archive failed (disk full, a refused path, a failed move or receipt). */
internal class StorageFault(cause: Exception) : Exception(cause.message, cause)

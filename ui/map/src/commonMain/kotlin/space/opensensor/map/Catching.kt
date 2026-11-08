package space.opensensor.map

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] for suspend calls in composables. Cancellation still ends the coroutine. */
internal inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

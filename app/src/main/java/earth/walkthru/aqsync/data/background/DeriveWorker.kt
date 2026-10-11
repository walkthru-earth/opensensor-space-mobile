package earth.walkthru.aqsync.data.background

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import earth.walkthru.aqsync.data.history.HistoryDeriver
import kotlinx.coroutines.CancellationException

/**
 * The history backfill that a post-sync derive deferred ([HistoryDeriver.deriveAfterSync]). A full
 * [HistoryDeriver.deriveStale] pass, queued by [AutoSyncScheduler.scheduleDeriveBackfill] to run
 * only while the phone charges.
 */
@HiltWorker
class DeriveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val historyDeriver: HistoryDeriver,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        historyDeriver.deriveStale()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "derive backfill failed: ${e::class.simpleName}: ${e.message}")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "DeriveWorker"
        const val MAX_RETRIES = 3
    }
}

package earth.walkthru.aqsync.data.storage

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import earth.walkthru.aqsync.storage.StorageJanitor

/**
 * Runs [StorageJanitor] once per process start (#60). Unique and KEEP, so a start while one is
 * queued or running adds nothing. It logs counts only, never paths or station ids.
 */
@HiltWorker
class StorageJanitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val janitor: StorageJanitor,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val report = janitor.run()
        Log.i(TAG, report.describe())
        return Result.success()
    }

    companion object {
        private const val TAG = "StorageJanitor"
        private const val NAME = "storage-janitor"

        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<StorageJanitorWorker>().build())
        }
    }
}

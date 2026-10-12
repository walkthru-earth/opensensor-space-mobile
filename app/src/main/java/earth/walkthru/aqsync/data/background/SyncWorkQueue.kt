package earth.walkthru.aqsync.data.background

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** One queued or running request, as much as the scheduler needs to know. */
data class QueuedWork(val running: Boolean, val enqueued: Boolean, val periodic: Boolean)

/** The WorkManager calls [AutoSyncScheduler] makes, behind a port so host tests use a fake. */
internal interface SyncWorkQueue {
    /** True while [uniqueName] has a request that has not finished. */
    suspend fun pending(uniqueName: String): Boolean

    /** Unique one-time work with `KEEP`. */
    fun enqueue(uniqueName: String, request: OneTimeWorkRequest)

    /** Unique periodic work with `KEEP`, so an update or restart does not reset the schedule. */
    fun enqueuePeriodic(uniqueName: String, request: PeriodicWorkRequest)

    fun cancel(uniqueName: String)

    fun observe(tag: String): Flow<List<QueuedWork>>
}

internal class WorkManagerQueue(private val context: Context) : SyncWorkQueue {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun pending(uniqueName: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first().any { !it.state.isFinished }

    override fun enqueue(uniqueName: String, request: OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueuePeriodic(uniqueName: String, request: PeriodicWorkRequest) {
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }

    override fun observe(tag: String): Flow<List<QueuedWork>> = workManager.getWorkInfosByTagFlow(tag).map { infos ->
        infos.map { QueuedWork(running = it.state == WorkInfo.State.RUNNING, enqueued = it.state == WorkInfo.State.ENQUEUED, periodic = it.periodicityInfo != null) }
    }
}

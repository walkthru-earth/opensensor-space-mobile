package space.opensensor.data.background

import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SyncWorker.doWork] hands the runner for each input. A one board run that names a board this
 * build cannot read must end with nothing done, never run as an every board run.
 */
class SyncWorkerTargetTest {
    private val runs = mutableListOf<WorkerRun>()
    private val record: suspend (WorkerRun) -> Result = { runs += it; Result.success() }

    @Test
    fun `an unreadable board runs nothing and succeeds`() = runTest {
        for (key in listOf(A, "phone:0f8e7d6c", "ble:")) {
            val result = SyncWorker.dispatch(workDataOf(SyncWorker.KEY_THING to key, SyncWorker.KEY_TRIGGER to "PRESENCE"), record)
            assertEquals(Result.success(), result)
        }
        assertTrue("no run may start for a board it cannot read, got $runs", runs.isEmpty())
    }

    @Test
    fun `a named board runs for that board only`() = runTest {
        SyncWorker.dispatch(workDataOf(SyncWorker.KEY_THING to "ble:$A"), record)
        assertEquals(listOf(WorkerRun(onlyAddress = A, allKnown = false, lanOnly = false, trigger = "MANUAL")), runs)
    }

    @Test
    fun `no board named runs every eligible board`() = runTest {
        SyncWorker.dispatch(workDataOf(), record)
        SyncWorker.dispatch(workDataOf(SyncWorker.KEY_ALL_KNOWN to true), record)
        SyncWorker.dispatch(workDataOf(SyncWorker.KEY_TRIGGER to "WIFI", SyncWorker.KEY_LAN_ONLY to true), record)
        assertEquals(
            listOf(
                WorkerRun(onlyAddress = null, allKnown = false, lanOnly = false, trigger = null),
                WorkerRun(onlyAddress = null, allKnown = true, lanOnly = false, trigger = "MANUAL"),
                WorkerRun(onlyAddress = null, allKnown = false, lanOnly = true, trigger = "WIFI"),
            ),
            runs,
        )
    }

    private companion object {
        const val A = "AA:BB:CC:DD:EE:01"
    }
}

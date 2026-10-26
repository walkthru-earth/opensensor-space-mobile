package space.opensensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import space.opensensor.storage.ByteBudgetLru
import space.opensensor.storage.MemoryPressure
import space.opensensor.storage.trimOnMemoryPressure
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class IosMemoryPressureTest {
    private fun postWarning() =
        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, null)

    @Test
    fun aMemoryWarningSignalsCritical() = runTest {
        IosMemoryPressure.install()
        IosMemoryPressure.install()
        val level = async(UnconfinedTestDispatcher(testScheduler)) { MemoryPressure.levels.first() }
        postWarning()
        assertEquals(MemoryPressure.Level.Critical, level.await())
    }

    @Test
    fun aMemoryWarningDropsATrimmableCache() = runTest {
        IosMemoryPressure.install()
        val lru = ByteBudgetLru<Int, String>(budgetBytes = 100) { _, _ -> 10 }
        repeat(5) { lru[it] = "day $it" }
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        scope.trimOnMemoryPressure({ level -> lru.trim(level) })
        postWarning()
        // The observer emits on the posting thread, the unconfined collector trims before this line.
        testScheduler.advanceUntilIdle()
        assertEquals(0, lru.size)
    }
}

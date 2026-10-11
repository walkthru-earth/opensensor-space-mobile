package earth.walkthru.aqsync.storage

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryPressureTest {
    @Test
    fun everyCacheTrimsOnEverySignalWhileTheScopeLives() = runTest {
        val levels = MutableSharedFlow<MemoryPressure.Level>()
        val first = mutableListOf<MemoryPressure.Level>()
        val second = mutableListOf<MemoryPressure.Level>()

        val job = backgroundScope.trimOnMemoryPressure(MemoryTrimmable { first += it }, MemoryTrimmable { second += it }, levels = levels)
        runCurrent()
        levels.emit(MemoryPressure.Level.Moderate)
        levels.emit(MemoryPressure.Level.Critical)
        runCurrent()

        val both = listOf(MemoryPressure.Level.Moderate, MemoryPressure.Level.Critical)
        assertEquals(both, first)
        assertEquals(both, second)

        job.cancel()
        runCurrent()
        // No collector is left, so nothing is delivered.
        levels.emit(MemoryPressure.Level.Critical)
        runCurrent()
        assertEquals(both, first)
    }

    @Test
    fun aTrimmedLruGivesItsBytesBack() = runTest {
        val levels = MutableSharedFlow<MemoryPressure.Level>()
        val lru = ByteBudgetLru<String, Long>(100) { _, bytes -> bytes }
        lru["a"] = 40
        lru["b"] = 40
        backgroundScope.trimOnMemoryPressure(MemoryTrimmable { lru.trim(it) }, levels = levels)
        runCurrent()

        levels.emit(MemoryPressure.Level.Moderate)
        runCurrent()
        assertEquals(40, lru.sizeBytes)

        levels.emit(MemoryPressure.Level.Critical)
        runCurrent()
        assertEquals(0, lru.sizeBytes)
    }
}

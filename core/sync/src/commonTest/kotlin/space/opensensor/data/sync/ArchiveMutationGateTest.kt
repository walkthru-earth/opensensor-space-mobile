package space.opensensor.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchiveMutationGateTest {
    @Test
    fun oneMutationPerStationAndOtherStationsRunMeanwhile() = runTest {
        val gate = ArchiveMutationGate()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { gate.withStation("a") { order += "a1"; release.await(); order += "a1 done" } }
        runCurrent()
        val second = launch { gate.withStation("a") { order += "a2" } }
        val other = launch { gate.withStation("b") { order += "b" } }
        runCurrent()
        assertEquals(listOf("a1", "b"), order)
        assertEquals(1, gate.activeCount())
        release.complete(Unit)
        first.join()
        second.join()
        other.join()
        assertEquals(listOf("a1", "b", "a1 done", "a2"), order)
        assertEquals(0, gate.activeCount())
    }

    @Test
    fun aFailingBlockReleasesTheStation() = runTest {
        val gate = ArchiveMutationGate()
        assertFailsWith<IllegalStateException> { gate.withStation("a") { error("disk") } }
        assertEquals(0, gate.activeCount())
        assertEquals(1, gate.withStation("a") { 1 })
    }
}

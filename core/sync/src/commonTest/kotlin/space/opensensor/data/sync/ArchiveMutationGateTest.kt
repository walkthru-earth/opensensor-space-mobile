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
    fun oneMutationPerThingAndOtherThingsRunMeanwhile() = runTest {
        val gate = ArchiveMutationGate()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { gate.withThing("a") { order += "a1"; release.await(); order += "a1 done" } }
        runCurrent()
        val second = launch { gate.withThing("a") { order += "a2" } }
        val other = launch { gate.withThing("b") { order += "b" } }
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
    fun aFailingBlockReleasesTheThing() = runTest {
        val gate = ArchiveMutationGate()
        assertFailsWith<IllegalStateException> { gate.withThing("a") { error("disk") } }
        assertEquals(0, gate.activeCount())
        assertEquals(1, gate.withThing("a") { 1 })
    }
}

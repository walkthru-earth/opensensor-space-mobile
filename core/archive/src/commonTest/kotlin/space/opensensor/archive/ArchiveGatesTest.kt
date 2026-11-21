package space.opensensor.archive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchiveGatesTest {
    @Test
    fun oneMutationPerThingAndOtherThingsRunMeanwhile() = runTest {
        val gate = ArchiveGates()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { gate.withThing("a") { order += "a1"; release.await(); order += "a1 done" } }
        runCurrent()
        val second = launch { gate.withThing("a") { order += "a2" } }
        val other = launch { gate.withThing("b") { order += "b" } }
        runCurrent()
        assertEquals(listOf("a1", "b"), order)
        assertEquals(1, gate.activeThings())
        release.complete(Unit)
        first.join()
        second.join()
        other.join()
        assertEquals(listOf("a1", "b", "a1 done", "a2"), order)
        assertEquals(0, gate.activeThings())
    }

    @Test
    fun aFailingBlockReleasesTheThing() = runTest {
        val gate = ArchiveGates()
        assertFailsWith<IllegalStateException> { gate.withThing("a") { error("disk") } }
        assertEquals(0, gate.activeThings())
        assertEquals(1, gate.withThing("a") { 1 })
    }
}

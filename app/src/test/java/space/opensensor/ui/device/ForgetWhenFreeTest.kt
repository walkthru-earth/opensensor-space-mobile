package space.opensensor.ui.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForgetWhenFreeTest {
    @Test
    fun `a forget confirmed while Collect all holds the link runs once the link frees`() = runTest {
        val busy = MutableStateFlow(true)
        var forgets = 0
        launch {
            forgetWhenFree(busy) {
                forgets++
                true
            }
        }
        runCurrent()
        assertEquals(0, forgets)
        busy.value = false
        runCurrent()
        assertEquals(1, forgets)
    }

    @Test
    fun `a forget on a free link runs at once`() = runTest {
        var forgets = 0
        forgetWhenFree(MutableStateFlow(false)) {
            forgets++
            true
        }
        assertEquals(1, forgets)
    }

    @Test
    fun `a forget refused because a command took the link first runs again once it frees`() = runTest {
        val busy = MutableStateFlow(false)
        var tries = 0
        val job = launch {
            forgetWhenFree(busy) {
                tries++
                // A command took the link between the busy check and the forget.
                if (tries == 1) busy.value = true
                tries > 1
            }
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, tries)
        assertTrue(job.isActive)
        busy.value = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, tries)
        assertFalse(job.isActive)
    }
}

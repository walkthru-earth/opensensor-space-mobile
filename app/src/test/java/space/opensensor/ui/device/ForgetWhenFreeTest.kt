package space.opensensor.ui.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForgetWhenFreeTest {
    @Test
    fun `a forget confirmed while Collect all holds the link runs once the link frees`() = runTest {
        val busy = MutableStateFlow(true)
        var forgets = 0
        launch { forgetWhenFree(busy) { forgets++ } }
        runCurrent()
        assertEquals(0, forgets)
        busy.value = false
        runCurrent()
        assertEquals(1, forgets)
    }

    @Test
    fun `a forget on a free link runs at once`() = runTest {
        var forgets = 0
        forgetWhenFree(MutableStateFlow(false)) { forgets++ }
        assertEquals(1, forgets)
    }
}

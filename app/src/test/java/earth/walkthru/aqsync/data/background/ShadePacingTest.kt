package earth.walkthru.aqsync.data.background

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShadePacingTest {
    /** Progress every 10 ms for 5 s, then the final value -1. */
    private fun progress() = flow {
        for (i in 0 until 500) {
            emit(i)
            delay(10)
        }
        emit(-1)
    }

    @Test
    fun `posts at most once per second and always the final value`() = runTest {
        val posts = mutableListOf<Pair<Long, Int>>()
        progress().postPaced(NOTIFY_INTERVAL_MS, MutableStateFlow(true), isFinal = { it < 0 }) { posts += testScheduler.currentTime to it }

        val perSecond = posts.filter { it.second >= 0 }.groupingBy { it.first / 1_000 }.eachCount()
        assertTrue("posts per second $perSecond", perSecond.values.all { it <= 1 })
        assertEquals(-1, posts.last().second)
        assertTrue(posts.size in 5..7)
    }

    @Test
    fun `a dark screen skips progress but not the final value`() = runTest {
        val posts = mutableListOf<Int>()
        progress().postPaced(NOTIFY_INTERVAL_MS, MutableStateFlow(false), isFinal = { it < 0 }) { posts += it }
        assertEquals(listOf(-1), posts)
    }

    @Test
    fun `turning the screen on posts the latest value at once`() = runTest {
        val screen = MutableStateFlow(false)
        val value = MutableStateFlow(1)
        val posts = mutableListOf<Pair<Long, Int>>()
        val job = launch { value.postPaced(NOTIFY_INTERVAL_MS, screen) { posts += testScheduler.currentTime to it } }
        advanceTimeBy(1_000)
        value.value = 2
        advanceTimeBy(1_000)
        assertTrue(posts.isEmpty())
        screen.value = true
        advanceTimeBy(1)
        assertEquals(listOf(2_000L to 2), posts)
        job.cancel()
    }
}

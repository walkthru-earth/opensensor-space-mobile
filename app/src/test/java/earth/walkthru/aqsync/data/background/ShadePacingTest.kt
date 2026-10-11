package earth.walkthru.aqsync.data.background

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    fun `screen off drops progress queued before the pacing deadline`() = runTest {
        val screen = MutableStateFlow(true)
        val posts = mutableListOf<Pair<Long, Int>>()
        val job = launch {
            flow {
                emit(0)
                delay(100)
                emit(1)
                delay(1_400)
                emit(-1)
            }.postPaced(NOTIFY_INTERVAL_MS, screen, isFinal = { it < 0 }) { posts += testScheduler.currentTime to it }
        }
        advanceTimeBy(200)
        screen.value = false
        advanceTimeBy(800)
        runCurrent()
        assertEquals(listOf(0L to 0), posts)
        job.join()
        assertEquals(listOf(0L to 0, 1_500L to -1), posts)
    }

    @Test
    fun `source completion preserves a final value without a suspension`() = runTest {
        val posts = mutableListOf<Int>()
        flowOf(0, -1).postPaced(NOTIFY_INTERVAL_MS, MutableStateFlow(false), isFinal = { it < 0 }) { posts += it }
        assertEquals(listOf(-1), posts)
    }

    @Test
    fun `screen state stays current while a completed source drains queued progress`() = runTest {
        val screen = MutableStateFlow(true)
        val posts = mutableListOf<Int>()
        val job = launch {
            flow { emit(0); delay(100); emit(1) }.postPaced(NOTIFY_INTERVAL_MS, screen) { posts += it }
        }
        advanceTimeBy(200)
        screen.value = false
        job.join()
        assertEquals(listOf(0), posts)
        assertEquals(0, screen.subscriptionCount.value)
    }

    @Test
    fun `a final value posts before the screen flow first emits`() = runTest {
        val posts = mutableListOf<Int>()
        flowOf(-1).postPaced(NOTIFY_INTERVAL_MS, flow { delay(100); emit(false) }, isFinal = { it < 0 }) { posts += it }
        assertEquals(listOf(-1), posts)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a quick screen off and on refresh respects the current interval`() = runTest {
        val screen = MutableStateFlow(true)
        val value = MutableStateFlow(0)
        val posts = mutableListOf<Pair<Long, Int>>()
        val job = launch { value.postPaced(NOTIFY_INTERVAL_MS, screen) { posts += testScheduler.currentTime to it } }
        advanceTimeBy(100)
        screen.value = false
        value.value = 1
        advanceTimeBy(100)
        screen.value = true
        advanceTimeBy(800)
        runCurrent()
        assertEquals(listOf(0L to 0, 1_000L to 1), posts)
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the pacing delay drops queued progress and releases collectors`() = runTest {
        val screen = MutableStateFlow(true)
        val value = MutableStateFlow(0)
        val posts = mutableListOf<Int>()
        val job = launch { value.postPaced(NOTIFY_INTERVAL_MS, screen) { posts += it } }
        runCurrent()
        value.value = 1
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(2_000)
        assertEquals(listOf(0), posts)
        assertEquals(0, screen.subscriptionCount.value)
        assertEquals(0, value.subscriptionCount.value)
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

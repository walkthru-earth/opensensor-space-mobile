package space.opensensor.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteBudgetLruTest {
    /** Each value's size is its own number, so the arithmetic reads straight off the test. */
    private fun lru(budget: Long) = ByteBudgetLru<String, Long>(budget) { _, bytes -> bytes }

    @Test
    fun putEvictsTheLeastRecentUntilWithinBudget() {
        val cache = lru(100)
        cache["a"] = 40
        cache["b"] = 40
        cache["c"] = 40

        assertEquals(listOf("b", "c"), cache.keys.toList())
        assertEquals(80, cache.sizeBytes)
        assertNull(cache["a"])
    }

    @Test
    fun getAndPutMakeAnEntryTheMostRecent() {
        val cache = lru(100)
        cache["a"] = 30
        cache["b"] = 30
        cache["c"] = 30
        assertEquals(30L, cache["a"])
        cache["b"] = 30

        assertEquals(listOf("c", "a", "b"), cache.keys.toList())
        // c is now the least recent, so it is the one a new entry pushes out.
        cache["d"] = 30
        assertEquals(listOf("a", "b", "d"), cache.keys.toList())
        assertEquals(90, cache.sizeBytes)
    }

    @Test
    fun anEntryLargerThanTheBudgetIsNotKept() {
        val cache = lru(100)
        cache["a"] = 50
        cache["huge"] = 101

        assertNull(cache["huge"])
        assertEquals(listOf("a"), cache.keys.toList())
        assertEquals(50, cache.sizeBytes)

        // Replacing a kept entry with an oversize value drops the old one too.
        cache["a"] = 200
        assertEquals(0, cache.size)
        assertEquals(0, cache.sizeBytes)

        cache["exact"] = 100
        assertEquals(100, cache.sizeBytes)
    }

    @Test
    fun sizeBytesFollowsReplaceRemoveAndRemoveAll() {
        val cache = lru(1000)
        cache["s1/a"] = 10
        cache["s1/b"] = 20
        cache["s2/a"] = 30
        assertEquals(60, cache.sizeBytes)

        cache["s1/a"] = 15
        assertEquals(65, cache.sizeBytes)
        assertEquals(3, cache.size)

        assertEquals(20L, cache.remove("s1/b"))
        assertNull(cache.remove("s1/b"))
        assertEquals(45, cache.sizeBytes)

        cache.removeAll { it.startsWith("s1/") }
        assertEquals(listOf("s2/a"), cache.keys.toList())
        assertEquals(30, cache.sizeBytes)

        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun aNegativeSizeCountsAsZero() {
        val cache = lru(10)
        cache["a"] = -5
        assertEquals(0, cache.sizeBytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun moderateTrimsToHalfTheBudgetOldestFirstAndCriticalClears() {
        val cache = lru(100)
        for ((i, key) in listOf("a", "b", "c", "d").withIndex()) cache[key] = 20L + i
        // 20 + 21 + 22 + 23 = 86 bytes.
        assertEquals(86, cache.sizeBytes)

        cache.trim(MemoryPressure.Level.Moderate)
        assertEquals(listOf("c", "d"), cache.keys.toList())
        assertEquals(45, cache.sizeBytes)
        assertTrue(cache.sizeBytes <= cache.budgetBytes / 2)

        cache.trim(MemoryPressure.Level.Critical)
        assertEquals(0, cache.size)
        assertEquals(0, cache.sizeBytes)

        // A trimmed cache fills again.
        cache["e"] = 10
        assertEquals(10L, cache["e"])
    }

    @Test
    fun aNegativeBudgetIsRefused() {
        assertFailsWith<IllegalArgumentException> { lru(-1) }
    }
}

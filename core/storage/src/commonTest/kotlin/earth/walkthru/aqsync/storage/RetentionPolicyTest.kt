package earth.walkthru.aqsync.storage

import earth.walkthru.aqsync.storage.RetentionPolicy.Companion.LOW_FREE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetentionPolicyTest {
    private val mib = 1024L * 1024
    private val policy = RetentionPolicy(derivedQuotaBytes = 64 * mib, mapCacheBytes = 20 * mib, blockCacheBytes = 16 * mib)

    @Test
    fun derivedQuotaLeavesRoomForTheMapAndTheBlockCache() {
        assertEquals(64 * mib, policy.derivedQuotaWithin(Long.MAX_VALUE))
        assertEquals(64 * mib, policy.derivedQuotaWithin(100 * mib))
        assertEquals(14 * mib, policy.derivedQuotaWithin(50 * mib))
        assertEquals(0L, policy.derivedQuotaWithin(30 * mib))
    }

    @Test
    fun blockCacheQuotaIsItsCapInsideTheAllowanceAfterTheMap() {
        val plenty = 10_000 * mib
        assertEquals(16 * mib, policy.blockCacheQuotaWithin(Long.MAX_VALUE, plenty))
        assertEquals(16 * mib, policy.blockCacheQuotaWithin(36 * mib, plenty))
        assertEquals(5 * mib, policy.blockCacheQuotaWithin(25 * mib, plenty))
        assertEquals(0L, policy.blockCacheQuotaWithin(10 * mib, plenty))
    }

    @Test
    fun blockCacheQuotaIsZeroWhileFreeSpaceIsLow() {
        assertEquals(16 * mib, policy.blockCacheQuotaWithin(Long.MAX_VALUE, LOW_FREE_BYTES))
        assertEquals(0L, policy.blockCacheQuotaWithin(Long.MAX_VALUE, LOW_FREE_BYTES - 1))
        assertEquals(0L, policy.blockCacheQuotaWithin(Long.MAX_VALUE, 0L))
    }

    @Test
    fun negativeQuotasAreRefused() {
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(blockCacheBytes = -1) }
    }
}

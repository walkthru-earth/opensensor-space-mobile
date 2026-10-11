package earth.walkthru.aqsync

import android.content.ComponentCallbacks2
import earth.walkthru.aqsync.storage.MemoryPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android trim levels to the shared memory signal (#61). */
@Suppress("DEPRECATION")
class MemoryTrimLevelTest {
    private fun level(trim: Int) = AqSyncApplication.memoryPressureLevel(trim)

    @Test
    fun aHiddenUiTrimsAndABackgroundAppClears() {
        assertEquals(MemoryPressure.Level.Moderate, level(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryPressure.Level.Critical, level(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryPressure.Level.Critical, level(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun aForegroundAppOnALowDeviceAlsoGivesMemoryBack() {
        assertNull(level(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.Level.Moderate, level(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.Level.Critical, level(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
    }
}

package earth.walkthru.aqsync.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreSettingsTest {
    @Test
    fun moduleIsGated() {
        assertEquals("core-settings", CoreSettings.MODULE)
    }

    @Test
    fun dataStorePreferencesResolveOnEveryTarget() {
        val key = stringPreferencesKey("unit_system")
        val prefs = mutablePreferencesOf(key to "metric")
        assertEquals("metric", prefs[key])
    }
}

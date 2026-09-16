package earth.walkthru.aqsync.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App-level preferences (DataStore Preferences). Today just the **Advanced mode** switch that
 * reveals the Debug destination. Nothing here is a device setting — those live on the device and
 * are read/written with `GET_CONFIG` / `SET_CONFIG`.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val advancedMode: Flow<Boolean> = context.settingsStore.data.map { it[ADVANCED_MODE] ?: false }

    suspend fun setAdvancedMode(on: Boolean) {
        context.settingsStore.edit { it[ADVANCED_MODE] = on }
    }

    private companion object {
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
    }
}

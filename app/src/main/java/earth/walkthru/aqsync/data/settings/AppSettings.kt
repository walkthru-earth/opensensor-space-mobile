package earth.walkthru.aqsync.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * How much the UI shows. Cosmetic only: every level reads the same data; higher levels *reveal*,
 * nothing the Simple experience needs depends on them (AGENTS.md).
 */
enum class DetailLevel(val title: String, val description: String) {
    SIMPLE("Simple", "What the air is like right now and today, in plain words, with the PM2.5 number. History and files stay one tap away."),
    DETAILED("Detailed", "Adds every measurement: PM10, PM1, particle counts, board temperature, battery and USB, storage counters, the file inspector and the History source card."),
    DEVELOPER("Developer", "Everything in Detailed plus the Debug screen: raw status JSON, the last frames on the link, the device's serial log and a raw command box."),
    ;

    val detailed: Boolean get() = this != SIMPLE
    val developer: Boolean get() = this == DEVELOPER
}

/**
 * App-level preferences (DataStore Preferences): the **detail level** (Simple · Detailed · Developer).
 * Nothing here is a device setting — those live on the device and are read/written with
 * `GET_CONFIG` / `SET_CONFIG`.
 *
 * The level replaced a Boolean "Advanced mode" (2026-09-17): an existing `advanced_mode = true` is
 * read as DEVELOPER until the user picks a level, so nobody loses their Debug screen.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val detailLevel: Flow<DetailLevel> = context.settingsStore.data.map { prefs ->
        prefs[DETAIL_LEVEL]?.let { name -> DetailLevel.entries.firstOrNull { it.name == name } }
            ?: if (prefs[ADVANCED_MODE] == true) DetailLevel.DEVELOPER else DetailLevel.SIMPLE
    }

    /** Detailed or Developer. */
    val detailed: Flow<Boolean> = detailLevel.map { it.detailed }

    /** Developer only — what the old Advanced switch meant: the Debug destination, raw views. */
    val advancedMode: Flow<Boolean> = detailLevel.map { it.developer }

    suspend fun setDetailLevel(level: DetailLevel) {
        context.settingsStore.edit {
            it[DETAIL_LEVEL] = level.name
            it[ADVANCED_MODE] = level.developer
        }
    }

    /** Kept for callers that only know the old switch: on → Developer, off → Simple. */
    suspend fun setAdvancedMode(on: Boolean) = setDetailLevel(if (on) DetailLevel.DEVELOPER else DetailLevel.SIMPLE)

    private companion object {
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
        val DETAIL_LEVEL = stringPreferencesKey("detail_level")
    }
}

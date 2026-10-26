package space.opensensor.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import space.opensensor.discover.DiscoverPrefs
import space.opensensor.discover.DiscoverSaved
import space.opensensor.discover.SavedCamera
import space.opensensor.publicfeed.FeedWindow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.discoverStore: DataStore<Preferences> by preferencesDataStore(name = "discover")

/**
 * What the Discover map remembers: the last camera, the chosen property and window, and whether
 * the first-run card was dismissed. Never a location fix.
 */
@Singleton
class DiscoverStore @Inject constructor(@ApplicationContext private val context: Context) : DiscoverPrefs {
    override suspend fun load(): DiscoverSaved {
        val prefs = context.discoverStore.data.first()
        val lat = prefs[LAT]
        val lng = prefs[LNG]
        val zoom = prefs[ZOOM]
        return DiscoverSaved(
            camera = if (lat != null && lng != null && zoom != null) SavedCamera(lat, lng, zoom) else null,
            property = prefs[PROPERTY],
            window = prefs[WINDOW]?.let { k -> FeedWindow.entries.firstOrNull { it.key == k } } ?: FeedWindow.DAY,
            firstRunDone = prefs[FIRST_RUN_DONE] ?: false,
        )
    }

    override suspend fun save(saved: DiscoverSaved) {
        context.discoverStore.edit { prefs ->
            saved.camera?.let {
                prefs[LAT] = it.lat
                prefs[LNG] = it.lng
                prefs[ZOOM] = it.zoom
            }
            saved.property?.let { prefs[PROPERTY] = it }
            prefs[WINDOW] = saved.window.key
            prefs[FIRST_RUN_DONE] = saved.firstRunDone
        }
    }

    private companion object {
        val LAT = doublePreferencesKey("camera_lat")
        val LNG = doublePreferencesKey("camera_lng")
        val ZOOM = doublePreferencesKey("camera_zoom")
        val PROPERTY = stringPreferencesKey("property")
        val WINDOW = stringPreferencesKey("window")
        val FIRST_RUN_DONE = booleanPreferencesKey("first_run_done")
    }
}

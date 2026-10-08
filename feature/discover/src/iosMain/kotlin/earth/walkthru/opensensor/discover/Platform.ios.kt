package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.FeedWindow
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

internal actual fun currentTimeZoneId(): String = NSTimeZone.localTimeZone.name

internal actual fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

// maplibre-native-ffi 0.202609.5 builds Apple targets with platform/qt/src/mln/bidi.cpp, a stub.
internal actual val mapShapesComplexText: Boolean = false

/** [DiscoverPrefs] in NSUserDefaults. Only the map camera and chip choices, never a location fix. */
class UserDefaultsDiscoverPrefs(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : DiscoverPrefs {
    override suspend fun load(): DiscoverSaved {
        val hasCamera = defaults.objectForKey(KEY_ZOOM) != null
        return DiscoverSaved(
            camera = if (hasCamera) SavedCamera(defaults.doubleForKey(KEY_LAT), defaults.doubleForKey(KEY_LNG), defaults.doubleForKey(KEY_ZOOM)) else null,
            property = defaults.stringForKey(KEY_PROPERTY),
            window = defaults.stringForKey(KEY_WINDOW)?.let { k -> FeedWindow.entries.firstOrNull { it.key == k } } ?: FeedWindow.DAY,
            firstRunDone = defaults.boolForKey(KEY_FIRST_RUN_DONE),
        )
    }

    override suspend fun save(saved: DiscoverSaved) {
        saved.camera?.let {
            defaults.setDouble(it.lat, KEY_LAT)
            defaults.setDouble(it.lng, KEY_LNG)
            defaults.setDouble(it.zoom, KEY_ZOOM)
        }
        if (saved.property != null) defaults.setObject(saved.property, KEY_PROPERTY)
        defaults.setObject(saved.window.key, KEY_WINDOW)
        defaults.setBool(saved.firstRunDone, KEY_FIRST_RUN_DONE)
    }

    private companion object {
        const val KEY_LAT = "discover.camera.lat"
        const val KEY_LNG = "discover.camera.lng"
        const val KEY_ZOOM = "discover.camera.zoom"
        const val KEY_PROPERTY = "discover.property"
        const val KEY_WINDOW = "discover.window"
        const val KEY_FIRST_RUN_DONE = "discover.firstRunDone"
    }
}

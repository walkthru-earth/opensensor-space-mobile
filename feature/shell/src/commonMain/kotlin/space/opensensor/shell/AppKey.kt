package space.opensensor.shell

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Every destination of the app, on both platforms. A key added elsewhere must join this hierarchy,
 * so [AppNavConfig] can save it without reflection (iOS has none).
 */
@Serializable
sealed interface AppKey : NavKey

/** The public sensor map. Always the start tab (docs/devices-and-discovery.md). */
@Serializable
data object DiscoverKey : AppKey

/** Find and pick a logger. The root of the My devices tab. */
@Serializable
data object ScanKey : AppKey

/** App settings, the root of the Settings tab. Device settings live in the Device section. */
@Serializable
data object SettingsKey : AppKey

/** Live readings and device health for the connected logger. */
@Serializable
data class TodayKey(val address: String) : AppKey

/** Day, week, month and year charts from the phone archive of one logger (no session needed). */
@Serializable
data class HistoryKey(val address: String) : AppKey

/** Manifest, sync and export for the connected logger. */
@Serializable
data class FilesKey(val address: String) : AppKey

/** Device configuration. Pairing mode, Wi-Fi, LAN token, bonds (protocol v2). */
@Serializable
data class DeviceKey(val address: String) : AppKey

/** Advanced mode only. Raw status JSON, frame log, device log, raw commands. */
@Serializable
data class DebugKey(val address: String) : AppKey

/** Detailed level and up. One archived file decoded by the app's Parquet reader. */
@Serializable
data class FileMetricsKey(val thing: String, val name: String) : AppKey

/**
 * The Add sensor wizard (`:feature:setup`), full screen over My devices. [resume] reopens Finish
 * setup for an added sensor at Name.
 */
@Serializable
data class SetupKey(val resume: String? = null) : AppKey

/** Saves every [AppKey] in a back stack, the same way on Android and iOS. */
@OptIn(ExperimentalSerializationApi::class)
val AppNavConfig: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed<AppKey>()
        }
    }
}

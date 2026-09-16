package earth.walkthru.aqsync.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Find and pick a logger. Home. */
@Serializable
data object ScanKey : NavKey

/** Live readings and device health for the connected logger. */
@Serializable
data class TodayKey(val address: String) : NavKey

/** Manifest, sync and export for the connected logger. */
@Serializable
data class FilesKey(val address: String) : NavKey

/** Device configuration: pairing mode, Wi-Fi, LAN token, bonds (protocol v2). */
@Serializable
data class DeviceKey(val address: String) : NavKey

/** Advanced mode only: raw status JSON, frame log, device log, raw commands. */
@Serializable
data class DebugKey(val address: String) : NavKey

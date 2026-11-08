package space.opensensor.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import space.opensensor.map.resources.Res
import space.opensensor.publicfeed.SequentialRamp

/**
 * The two map styles. The basemap reads OpenStreetMap PMTiles on source.coop, anonymous and read
 * only. The paper style has no source at all, for when public data is off or the basemap cannot load.
 */
object MapStyles {
    /** The basemap style, light or dark, bundled as a Compose resource (tools/discover-fixture). */
    suspend fun basemap(dark: Boolean): String {
        val flavour = if (dark) "dark" else "light"
        return Res.readBytes("files/basemap/$flavour.json").decodeToString()
    }

    /** A style with no network sources, one background of [color]. */
    fun paper(color: Color): String =
        "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"paper\",\"type\":\"background\",\"paint\":{\"background-color\":\"${hex(color)}\"}}]}"

    private fun hex(color: Color): String {
        fun c(v: Float) = (v * 255).toInt().coerceIn(0, 255)
        return SequentialRamp.hex((0xFF shl 24) or (c(color.red) shl 16) or (c(color.green) shl 8) or c(color.blue))
    }
}

/**
 * The style for the system theme. The basemap when [networkAllowed] (the Settings switch for public
 * data), otherwise or when it fails to load the paper style in [paper]. Null while it loads.
 */
@Composable
fun rememberMapStyle(networkAllowed: Boolean, paper: Color = MaterialTheme.colorScheme.surfaceContainerLow): String? {
    val dark = isSystemInDarkTheme()
    val style by produceState<String?>(null, networkAllowed, dark) {
        value = if (networkAllowed) catchingNonCancel { MapStyles.basemap(dark) }.getOrNull() ?: MapStyles.paper(paper) else MapStyles.paper(paper)
    }
    return style
}

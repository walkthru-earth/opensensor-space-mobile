package space.opensensor.device

import androidx.compose.runtime.Composable
import space.opensensor.data.protocol.LocationConfig
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.publicfeed.H3Index

/** No phone coordinates enter UI state. The candidate is already a cell. */
data class DeviceLocationDraft(
    val cell: String = "",
    val resolution: Int = 5,
    val country: String = "",
    val needsFix: Boolean = false,
) {
    fun atResolution(value: Int): DeviceLocationDraft {
        require(value in 0..15)
        val index = cell.takeIf { it.isNotEmpty() }?.let(H3Index::fromHex)
        return when {
            index == null -> copy(resolution = value)
            value <= H3Index.resolution(index) -> copy(cell = H3Index.toHex(H3Index.parent(index, value)), resolution = value, needsFix = false)
            else -> copy(resolution = value, needsFix = needsFix || value > resolution)
        }
    }

    /** A late fix for another precision selection must not restore finer location. */
    fun withFix(value: String, requestedResolution: Int): DeviceLocationDraft =
        if (requestedResolution != resolution) this else copy(cell = value, needsFix = false)

    companion object {
        fun from(config: LocationConfig) = DeviceLocationDraft(
            cell = config.cell.orEmpty(),
            resolution = config.resolution?.takeIf { it in 0..15 } ?: 5,
            country = config.country.orEmpty(),
        )
    }
}

enum class DeviceLocationResult { SAVED, FAILED, NOT_CONFIRMED }

data class DeviceLocationState(
    val supported: Boolean? = null,
    val working: Boolean = false,
    val draft: DeviceLocationDraft = DeviceLocationDraft(),
    val result: DeviceLocationResult? = null,
)

/** Tap-only, cancellable foreground location. The implementation returns only a coarsened cell. */
class DeviceLocationRequest(val access: RuntimeAccess, val cell: suspend (resolution: Int) -> String?)

@Composable
internal expect fun rememberDeviceLocationRequest(): DeviceLocationRequest

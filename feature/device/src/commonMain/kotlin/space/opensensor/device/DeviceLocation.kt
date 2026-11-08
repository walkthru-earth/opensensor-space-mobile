package space.opensensor.device

import androidx.compose.runtime.Composable
import space.opensensor.data.protocol.LocationConfig
import space.opensensor.model.H3Cell
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.publicfeed.H3Index

/** No phone coordinates enter UI state. The candidate is already a cell. */
data class DeviceLocationDraft(
    val cell: String = "",
    val resolution: Int = RESOLUTION,
    val country: String = "",
    val needsFix: Boolean = false,
) {
    /**
     * The draft at [value], which must be [RESOLUTION]. A finer cell is coarsened to it. A coarser cell
     * cannot be made finer, so the draft keeps it and needs a fresh fix before Save.
     */
    fun atResolution(value: Int): DeviceLocationDraft {
        require(value == RESOLUTION) { "the location resolution is fixed at $RESOLUTION" }
        val index = cell.takeIf { it.isNotEmpty() }?.let(H3Index::fromHex)
        return when {
            index == null -> copy(resolution = value)
            value <= H3Index.resolution(index) -> copy(cell = H3Index.toHex(H3Index.parent(index, value)), resolution = value, needsFix = false)
            else -> copy(resolution = value, needsFix = true)
        }
    }

    /** A fix taken at another resolution is dropped, so it never restores a finer location. */
    fun withFix(value: String, requestedResolution: Int): DeviceLocationDraft =
        if (requestedResolution != resolution) this else copy(cell = value, needsFix = false)

    companion object {
        /** The H3 resolution of every Thing, fixed or moving. The app sends no other. */
        const val RESOLUTION = H3Cell.RESOLUTION

        /** The board's page 1 brought to [RESOLUTION]. A value that is no H3 cell reads as unset. */
        fun from(config: LocationConfig) = DeviceLocationDraft(
            cell = config.cell?.takeIf { H3Cell.isCellHex(it) }.orEmpty(),
            country = config.country.orEmpty(),
        ).atResolution(RESOLUTION)
    }
}

enum class DeviceLocationResult { SAVED, FAILED, NOT_CONFIRMED }

data class DeviceLocationState(
    val supported: Boolean? = null,
    val working: Boolean = false,
    val draft: DeviceLocationDraft = DeviceLocationDraft(),
    val result: DeviceLocationResult? = null,
    /** The board confirmed a valid `location.cell`, read back or acknowledged after Save. */
    val saved: Boolean = false,
)

/**
 * A valid H3 cell whose resolution is the one the board reports for it and no finer than the maximum.
 * Country is optional and does not count.
 */
fun LocationConfig.hasCell(): Boolean {
    val hex = cell?.takeIf { Regex("[0-9a-f]{15}").matches(it) } ?: return false
    val index = H3Index.fromHex(hex)
    if (!H3Index.isCell(index)) return false
    val res = H3Index.resolution(index)
    return (cellResolution == null || cellResolution == res) && res <= (resolution ?: 15)
}

/** Tap-only, cancellable foreground location. The implementation returns only a coarsened cell. */
class DeviceLocationRequest(val access: RuntimeAccess, val cell: suspend (resolution: Int) -> String?)

@Composable
internal expect fun rememberDeviceLocationRequest(): DeviceLocationRequest

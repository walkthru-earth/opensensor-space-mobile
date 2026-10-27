package space.opensensor.setup

import space.opensensor.data.protocol.ConfigFields
import space.opensensor.permissions.UserAction
import space.opensensor.thing.KindId
import space.opensensor.thing.OpenSensorBoardKind

/**
 * One step of a Thing kind's setup. First setup walks the kind's list in order, and the Device
 * settings page reuses the same forms for [Location] and [Fields].
 *
 * [Permission] and [Pair] are not pages. A [Permission] step binds `PermissionTriggers` to the
 * action of the page after it, so the access is asked for from that tap and never as a wall. [Pair]
 * runs when a sensor is picked on [Find]. Sharing is never a step, upload is turned on only from that
 * Thing's settings.
 */
sealed interface SetupStep {
    val id: String
    val skippable: Boolean

    /** Pages the user sees. [Permission] and [Pair] are not. */
    val isPage: Boolean get() = this !is Permission && this !is Pair

    /** Asks for the access [action] needs, from the next page's own tap. */
    data class Permission(val action: UserAction) : SetupStep {
        override val id = "permission:${action.name.lowercase()}"
        override val skippable = false
    }

    /** Nearby sensors of this kind. Picking one runs [Pair]. */
    data object Find : SetupStep {
        override val id = "find"
        override val skippable = false
    }

    /** Opens the link and waits for INFO. The OS pairing dialog shows here. */
    data object Pair : SetupStep {
        override val id = "pair"
        override val skippable = false
    }

    /** The phone only nickname. */
    data object Name : SetupStep {
        override val id = "name"
        override val skippable = false
    }

    /** The sensor's H3 cell and country, through `DeviceLocationForm`. */
    data object Location : SetupStep {
        override val id = "location"
        override val skippable = true
    }

    /** A group of `SET_CONFIG` keys from the `ConfigFields` table, named [id]. */
    data class Fields(override val id: String, val keys: List<String>) : SetupStep {
        override val skippable = true
    }

    /** The summary. Done may at most link to the sharing setting. */
    data object Done : SetupStep {
        override val id = "done"
        override val skippable = false
    }
}

/** The setup steps of each Thing kind the app knows. */
object SetupFlows {
    /** The Wi-Fi page of the board, joined over Bluetooth, then Sync over Wi-Fi. */
    val WIFI = SetupStep.Fields("wifi", listOf(ConfigFields.WIFI_ON, ConfigFields.WIFI_SSID, ConfigFields.WIFI_PSK, ConfigFields.LAN_ON))

    /** `FIND → NAME → LOCATION → WIFI → DONE`, with Bluetooth asked for from Find and local network from Turn on Wi-Fi sync. */
    val BOARD: List<SetupStep> = listOf(
        SetupStep.Permission(UserAction.FIND_SENSORS),
        SetupStep.Find,
        SetupStep.Pair,
        SetupStep.Name,
        SetupStep.Location,
        SetupStep.Permission(UserAction.WIFI_ACTION),
        WIFI,
        SetupStep.Done,
    )

    private val byKind: Map<KindId, List<SetupStep>> = mapOf(OpenSensorBoardKind.id to BOARD)

    fun of(kind: KindId): List<SetupStep>? = byKind[kind]
}

/** The access the step right before [page] binds, if any. */
fun List<SetupStep>.accessFor(page: SetupStep): UserAction? {
    val at = indexOf(page)
    return (getOrNull(at - 1) as? SetupStep.Permission)?.action
}

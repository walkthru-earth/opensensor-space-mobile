package space.opensensor.device

import space.opensensor.data.protocol.BoardSettings
import space.opensensor.data.protocol.ConfigField
import space.opensensor.data.protocol.FieldType
import space.opensensor.data.sync.PlanField

enum class DeviceBoardResult { SAVED, FAILED, NOT_CONFIRMED, READ_FAILED }

/**
 * The board settings page (page 7) as the board reported it, with the user's unsaved [draft].
 * [supported] stays null until the page was read, and is false when the platform has no page 7.
 */
data class DeviceBoardState(
    val supported: Boolean? = null,
    val working: Boolean = false,
    val stored: BoardSettings = BoardSettings.EMPTY,
    val draft: Map<String, String> = emptyMap(),
    val result: DeviceBoardResult? = null,
) {
    /** The value a form shows for [key], the draft over the stored one, empty when neither holds one. */
    fun value(key: String): String = draft[key] ?: stored[key].orEmpty()

    /** The drafted values of [fields] that differ from the stored ones. An action is never drafted. */
    fun changes(fields: List<PlanField>): List<Pair<ConfigField, String>> = fields
        .filter { it.field.type != FieldType.Action }
        .mapNotNull { f -> draft[f.field.key]?.takeIf { it != stored[f.field.key].orEmpty() }?.let { f.field to it } }
}

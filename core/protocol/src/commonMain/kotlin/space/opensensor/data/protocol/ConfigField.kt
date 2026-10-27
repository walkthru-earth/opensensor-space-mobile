package space.opensensor.data.protocol

/** The value shape of one `SET_CONFIG` key. */
sealed interface FieldType {
    /** UTF-8 text of [minBytes] to [maxBytes] bytes on one line. */
    data class Text(val minBytes: Int, val maxBytes: Int) : FieldType

    /** Exactly [length] ASCII digits. */
    data class Digits(val length: Int) : FieldType

    /** A whole number from [min] to [max]. */
    data class IntBetween(val min: Int, val max: Int) : FieldType

    /** One of [values], in wire spelling. */
    data class Choice(val values: List<String>) : FieldType

    /** `0` or `1`. */
    data object Toggle : FieldType

    /** Not stored. Sending `1` does something once. */
    data object Action : FieldType

    /** An H3 cell in lowercase hex, or empty to clear it. */
    data object H3Cell : FieldType

    /** An uppercase ISO 3166 alpha 2 code, or empty. */
    data object Country : FieldType
}

/** The settings card a key belongs to, so a refusal names the card instead of the wire key. */
enum class ConfigGroup { PAIRING, WIFI, LAN, LOCATION, SENSOR, OTHER }

/** When the firmware applies a key. */
enum class Applies { IMMEDIATELY, NEXT_SAMPLE, NEXT_BOOT, ONCE }

/** Whether a key asks the user before it is sent. */
sealed interface Confirm {
    data object Never : Confirm

    data object Always : Confirm

    /** Only when the value equals [value], for example `ble.pair=none`. */
    data class When(val value: String) : Confirm
}

/**
 * One `SET_CONFIG` key the app may send.
 *
 * [secret] keys travel only over Bluetooth and never reach a log or frame. [disrupts] keys cut
 * the link or a transfer and wait for a quiet link (`DeviceConfigUiState.canDisrupt`).
 */
data class ConfigField(
    val key: String,
    val type: FieldType,
    val applies: Applies,
    val secret: Boolean = false,
    val confirm: Confirm = Confirm.Never,
    val disrupts: Boolean = false,
) {
    /** Whether sending [value] for this key needs a confirmation first. */
    fun needsConfirm(value: String): Boolean = when (val c = confirm) {
        Confirm.Never -> false
        Confirm.Always -> true
        is Confirm.When -> c.value == value
    }
}

/**
 * The app allowlist of `SET_CONFIG` keys, mirroring [ConfigPatch.Builder] one key per line.
 *
 * A schema from a board (for example a future config block in `INFO`) can only make a key
 * stricter. [merge] keeps the app's type and timing, turns a key secret or adds a confirmation
 * when the schema asks, and drops any key the app does not know. These floor rules are why a
 * board can never lower the bar for a secret or a destructive action.
 */
object ConfigFields {
    const val BLE_PAIR = "ble.pair"
    const val BLE_PIN = "ble.pin"
    const val BLE_CLEAR_BONDS = "ble.clear_bonds"
    const val WIFI_ON = "wifi.on"
    const val WIFI_SSID = "wifi.ssid"
    const val WIFI_PSK = "wifi.psk"
    const val LAN_ON = "lan.on"
    const val LAN_ROTATE_TOKEN = "lan.rotate_token"
    const val LOCATION_CELL = "location.cell"
    const val LOCATION_RESOLUTION = "location.resolution"
    const val LOCATION_COUNTRY = "location.country"
    const val SENSOR_VENDOR = "sensor.vendor"
    const val SENSOR_MODEL = "sensor.model"
    const val SENSOR_SERIAL = "sensor.serial"
    const val SENSOR_BATCH_CANDIDATE = "sensor.batch_candidate"

    val ALL: List<ConfigField> = listOf(
        ConfigField(BLE_PAIR, FieldType.Choice(PairMode.entries.map { it.wire }), Applies.NEXT_BOOT, confirm = Confirm.When(PairMode.NONE.wire)),
        ConfigField(BLE_PIN, FieldType.Digits(6), Applies.NEXT_BOOT, secret = true),
        ConfigField(BLE_CLEAR_BONDS, FieldType.Action, Applies.ONCE, confirm = Confirm.Always, disrupts = true),
        ConfigField(WIFI_ON, FieldType.Toggle, Applies.IMMEDIATELY),
        ConfigField(WIFI_SSID, FieldType.Text(1, ConfigPatch.SSID_MAX_BYTES), Applies.IMMEDIATELY),
        ConfigField(WIFI_PSK, FieldType.Text(0, ConfigPatch.PSK_MAX_BYTES), Applies.IMMEDIATELY, secret = true),
        ConfigField(LAN_ON, FieldType.Toggle, Applies.IMMEDIATELY),
        ConfigField(LAN_ROTATE_TOKEN, FieldType.Action, Applies.ONCE, confirm = Confirm.Always, disrupts = true),
        ConfigField(LOCATION_CELL, FieldType.H3Cell, Applies.NEXT_SAMPLE),
        ConfigField(LOCATION_RESOLUTION, FieldType.IntBetween(0, 15), Applies.NEXT_SAMPLE),
        ConfigField(LOCATION_COUNTRY, FieldType.Country, Applies.NEXT_SAMPLE),
        ConfigField(SENSOR_VENDOR, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_MODEL, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_SERIAL, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_BATCH_CANDIDATE, FieldType.Toggle, Applies.IMMEDIATELY),
    )

    private val byKey: Map<String, ConfigField> = ALL.associateBy { it.key }

    fun of(key: String): ConfigField? = byKey[key]

    /** The card [key] is set from, by its prefix. A key the app does not know is [ConfigGroup.OTHER]. */
    fun groupOf(key: String): ConfigGroup = when (key.substringBefore('.', missingDelimiterValue = "")) {
        "ble" -> ConfigGroup.PAIRING
        "wifi" -> ConfigGroup.WIFI
        "lan" -> ConfigGroup.LAN
        "location", "thing" -> ConfigGroup.LOCATION
        "sensor" -> ConfigGroup.SENSOR
        else -> ConfigGroup.OTHER
    }

    /** Keys whose values are secrets under the app floor alone. */
    val SECRET_KEYS: Set<String> = ALL.filter { it.secret }.map { it.key }.toSet()

    /**
     * The fields a board may offer, after the floor rules. An unknown key fails closed and is
     * left out. A known key keeps the app's type, timing and every protection the app sets, and
     * gains any extra protection [schema] asks for.
     */
    fun merge(schema: List<ConfigField>): List<ConfigField> = schema.mapNotNull { offered ->
        val floor = byKey[offered.key] ?: return@mapNotNull null
        floor.copy(
            secret = floor.secret || offered.secret,
            confirm = stricter(floor.confirm, offered.confirm),
            disrupts = floor.disrupts || offered.disrupts,
        )
    }

    /** Secret keys once [schema] is merged. Never smaller than [SECRET_KEYS]. */
    fun secretKeys(schema: List<ConfigField>): Set<String> = SECRET_KEYS + merge(schema).filter { it.secret }.map { it.key }

    private fun stricter(a: Confirm, b: Confirm): Confirm = when {
        a == Confirm.Always || b == Confirm.Always -> Confirm.Always
        a is Confirm.When && b is Confirm.When && a != b -> Confirm.Always
        a is Confirm.When -> a
        b is Confirm.When -> b
        else -> Confirm.Never
    }
}

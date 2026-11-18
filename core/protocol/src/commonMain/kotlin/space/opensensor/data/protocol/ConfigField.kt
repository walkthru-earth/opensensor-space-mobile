package space.opensensor.data.protocol

import space.opensensor.model.SiteField

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
enum class ConfigGroup { PAIRING, WIFI, LAN, LOCATION, SITE, SENSOR, OTHER }

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
 * the link or a transfer and wait for a quiet link (`DeviceConfigUiState.canDisrupt`). [page] is
 * the `GET_CONFIG` page that reads a board key back, null for the app's own keys.
 */
data class ConfigField(
    val key: String,
    val type: FieldType,
    val applies: Applies,
    val secret: Boolean = false,
    val confirm: Confirm = Confirm.Never,
    val disrupts: Boolean = false,
    val page: Int? = null,
) {
    /** Whether sending [value] for this key needs a confirmation first. */
    fun needsConfirm(value: String): Boolean = when (val c = confirm) {
        Confirm.Never -> false
        Confirm.Always -> true
        is Confirm.When -> c.value == value
    }
}

/**
 * The app allowlist of `SET_CONFIG` keys, mirroring [ConfigPatch.Builder] one key per line, and
 * the floor rules for the keys a board template declares in `firmware.config_keys`.
 *
 * [merge] keeps every app key at the app's type, timing and protections, and accepts a board key
 * only outside [RESERVED_PREFIXES], never secret, with a type that parses, read back from
 * [Request.GetConfigPage.BOARD_SETTINGS]. A board `action` always confirms. These rules are why a
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
    const val THING_MOBILITY = "thing.mobility"
    private const val PUBLISHED_RES = space.opensensor.model.H3Cell.RESOLUTION
    const val SENSOR_VENDOR = "sensor.vendor"
    const val SENSOR_MODEL = "sensor.model"
    const val SENSOR_SERIAL = "sensor.serial"
    const val SENSOR_BATCH_CANDIDATE = "sensor.batch_candidate"
    const val OWNER_TITLE = "owner.title"
    const val OWNER_PROVIDER = "owner.provider"
    const val OWNER_URL = "owner.url"
    const val OWNER_DESCRIPTION = "owner.description"
    const val THING_DEPLOYMENT_ID = "thing.deployment_id"

    /** Page 1 `thing.setup_ack`, the done bit of each step in the template `firmware.setup` order. */
    const val THING_SETUP_ACK = "thing.setup_ack"

    /** Page 1 `thing.setup_v`, the [SetupAck.version] of the step list the bits refer to. */
    const val THING_SETUP_V = "thing.setup_v"

    /**
     * Key prefixes that only the app allowlist may use. A board key under one of them is refused,
     * so a template can never add a pairing, Wi-Fi, LAN, location, Thing, owner or sensor key.
     */
    val RESERVED_PREFIXES: List<String> = listOf("ble.", "wifi.", "lan.", "location.", "thing.", "owner.", "sensor.")

    /** The longest board key name the app accepts, in bytes. */
    const val DECLARED_KEY_MAX_BYTES = 48

    /** The most dot-separated parts a board key name may have, so [BoardSettings] reads every one back. */
    const val DECLARED_KEY_MAX_PARTS = 4

    /** The text limit of a declared `text` key that names no range, in UTF-8 bytes. */
    const val DECLARED_TEXT_MAX_BYTES = 64

    /** The wire key of each owner site value. */
    fun siteKey(field: SiteField): String = when (field) {
        SiteField.TITLE -> OWNER_TITLE
        SiteField.PROVIDER -> OWNER_PROVIDER
        SiteField.URL -> OWNER_URL
        SiteField.DESCRIPTION -> OWNER_DESCRIPTION
        SiteField.DEPLOYMENT_ID -> THING_DEPLOYMENT_ID
    }

    /**
     * The owner site keys. The firmware keeps them in their own record, so the phone sends them in
     * their own `SET_CONFIG`, never with another key ([ConfigPatch.Builder.build] refuses a mix).
     */
    val SITE_KEYS: Set<String> = SiteField.entries.map(::siteKey).toSet()

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
        // The firmware accepts 8 only and answers any other value with ERROR 12.
        ConfigField(LOCATION_RESOLUTION, FieldType.IntBetween(PUBLISHED_RES, PUBLISHED_RES), Applies.NEXT_SAMPLE),
        ConfigField(LOCATION_COUNTRY, FieldType.Country, Applies.NEXT_SAMPLE),
        ConfigField(THING_MOBILITY, FieldType.Choice(ThingMobility.entries.map { it.wire }), Applies.NEXT_SAMPLE),
        ConfigField(SENSOR_VENDOR, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_MODEL, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_SERIAL, FieldType.Text(1, 32), Applies.IMMEDIATELY),
        ConfigField(SENSOR_BATCH_CANDIDATE, FieldType.Toggle, Applies.IMMEDIATELY),
        // Empty clears each one. SiteRules holds the character rules.
        ConfigField(OWNER_TITLE, FieldType.Text(0, SiteField.TITLE.maxBytes), Applies.IMMEDIATELY),
        ConfigField(OWNER_PROVIDER, FieldType.Text(0, SiteField.PROVIDER.maxBytes), Applies.IMMEDIATELY),
        ConfigField(OWNER_URL, FieldType.Text(0, SiteField.URL.maxBytes), Applies.IMMEDIATELY),
        ConfigField(OWNER_DESCRIPTION, FieldType.Text(0, SiteField.DESCRIPTION.maxBytes), Applies.IMMEDIATELY),
        ConfigField(THING_DEPLOYMENT_ID, FieldType.Text(0, SiteField.DEPLOYMENT_ID.maxBytes), Applies.NEXT_SAMPLE),
        ConfigField(THING_SETUP_ACK, FieldType.IntBetween(0, SetupAck.MASK_MAX), Applies.IMMEDIATELY),
        ConfigField(THING_SETUP_V, FieldType.IntBetween(1, SetupAck.MASK_MAX), Applies.IMMEDIATELY),
    )

    private val byKey: Map<String, ConfigField> = ALL.associateBy { it.key }

    fun of(key: String): ConfigField? = byKey[key]

    /** The card [key] is set from, by its prefix. A key the app does not know is [ConfigGroup.OTHER]. */
    fun groupOf(key: String): ConfigGroup = if (key in SITE_KEYS) {
        ConfigGroup.SITE
    } else if (key == THING_SETUP_ACK || key == THING_SETUP_V) {
        ConfigGroup.OTHER
    } else {
        when (key.substringBefore('.', missingDelimiterValue = "")) {
            "ble" -> ConfigGroup.PAIRING
            "wifi" -> ConfigGroup.WIFI
            "lan" -> ConfigGroup.LAN
            "location", "thing" -> ConfigGroup.LOCATION
            "sensor" -> ConfigGroup.SENSOR
            else -> ConfigGroup.OTHER
        }
    }

    /** Keys whose values are secrets under the app floor alone. */
    val SECRET_KEYS: Set<String> = ALL.filter { it.secret }.map { it.key }.toSet()

    /** Whether [key] is under one of the [RESERVED_PREFIXES]. */
    fun isReserved(key: String): Boolean = RESERVED_PREFIXES.any { key.startsWith(it) }

    /**
     * The fields a board may offer, after the floor rules. Never secret, so [SECRET_KEYS] stays the
     * whole secret set.
     *
     * 1. A field marked secret, or a key in [SECRET_KEYS], is dropped. Secrets are never declarable.
     * 2. An app key keeps the app's type, timing and protections, and gains a stricter
     *    confirmation or `disrupts` when [schema] asks.
     * 3. A board key is kept when it is outside [RESERVED_PREFIXES], has a valid name of at most
     *    [DECLARED_KEY_MAX_PARTS] parts and a board type, and reads back from
     *    [Request.GetConfigPage.BOARD_SETTINGS]. A board [FieldType.Action] always confirms,
     *    disrupts and applies [Applies.ONCE].
     * 4. Anything else is dropped, and a repeated key keeps its first entry.
     */
    fun merge(schema: List<ConfigField>): List<ConfigField> = schema.mapNotNull(::floor).distinctBy { it.key }

    private fun floor(offered: ConfigField): ConfigField? {
        if (offered.secret || offered.key in SECRET_KEYS) return null
        byKey[offered.key]?.let { app ->
            return app.copy(
                confirm = stricter(app.confirm, offered.confirm),
                disrupts = app.disrupts || offered.disrupts,
            )
        }
        if (isReserved(offered.key) || !isBoardKeyName(offered.key)) return null
        if (offered.page != Request.GetConfigPage.BOARD_SETTINGS) return null
        if (!isBoardType(offered.type)) return null
        return if (offered.type == FieldType.Action) {
            offered.copy(applies = Applies.ONCE, confirm = Confirm.Always, disrupts = true)
        } else {
            if (offered.applies == Applies.ONCE) return null
            offered
        }
    }

    /**
     * One `firmware.config_keys` entry as a [ConfigField], or null when it does not parse.
     *
     * - [type] `boolean` is [FieldType.Toggle], or [FieldType.Action] when [applies] is `action`.
     * - `integer` needs [values] of two whole numbers, the range, and is [FieldType.IntBetween].
     * - `enum` needs [values], the choices, and is [FieldType.Choice].
     * - `text` takes an optional two number byte range in [values], else 0 to [DECLARED_TEXT_MAX_BYTES].
     * - [applies] is `immediately`, `next sample`, `next boot` or `action`. Only `boolean` may be an
     *   `action`.
     *
     * Any other type, a decimal among them, does not parse. The result still goes through [merge].
     */
    fun declared(key: String, type: String, values: List<String>, applies: String, page: Int?): ConfigField? {
        val timing = when (applies) {
            "immediately" -> Applies.IMMEDIATELY
            "next sample" -> Applies.NEXT_SAMPLE
            "next boot" -> Applies.NEXT_BOOT
            "action" -> Applies.ONCE
            else -> return null
        }
        if (timing == Applies.ONCE && type != "boolean") return null
        val fieldType: FieldType = when (type) {
            "boolean" -> if (values.isNotEmpty()) {
                return null
            } else if (timing == Applies.ONCE) {
                FieldType.Action
            } else {
                FieldType.Toggle
            }
            "integer" -> range(values)?.let { (min, max) -> FieldType.IntBetween(min, max) } ?: return null
            "enum" -> FieldType.Choice(values)
            "text" -> when {
                values.isEmpty() -> FieldType.Text(0, DECLARED_TEXT_MAX_BYTES)
                else -> range(values)?.let { (min, max) -> FieldType.Text(min, max) } ?: return null
            }
            else -> return null
        }
        return ConfigField(key, fieldType, timing, page = page).takeIf { isBoardType(it.type) }
    }

    private fun range(values: List<String>): Pair<Int, Int>? {
        if (values.size != 2) return null
        val min = values[0].toIntOrNull() ?: return null
        val max = values[1].toIntOrNull() ?: return null
        return (min to max).takeIf { min <= max }
    }

    private val BOARD_KEY = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

    private fun isBoardKeyName(key: String): Boolean = key.length <= DECLARED_KEY_MAX_BYTES &&
        key.count { it == '.' } < DECLARED_KEY_MAX_PARTS &&
        BOARD_KEY.matches(key)

    private fun isBoardType(type: FieldType): Boolean = when (type) {
        FieldType.Toggle, FieldType.Action -> true
        is FieldType.IntBetween -> type.min <= type.max
        is FieldType.Text -> type.minBytes in 0..type.maxBytes && type.maxBytes <= DECLARED_TEXT_MAX_BYTES
        is FieldType.Choice -> type.values.isNotEmpty() && type.values.distinct().size == type.values.size &&
            type.values.all { it.isNotEmpty() && it.encodeToByteArray().size <= DECLARED_TEXT_MAX_BYTES && it.none { c -> c < ' ' || c == '\u007F' } }
        is FieldType.Digits, FieldType.H3Cell, FieldType.Country -> false
    }

    private fun stricter(a: Confirm, b: Confirm): Confirm = when {
        a == Confirm.Always || b == Confirm.Always -> Confirm.Always
        a is Confirm.When && b is Confirm.When && a != b -> Confirm.Always
        a is Confirm.When -> a
        b is Confirm.When -> b
        else -> Confirm.Never
    }
}

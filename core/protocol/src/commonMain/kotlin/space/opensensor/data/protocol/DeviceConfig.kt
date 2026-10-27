package space.opensensor.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `CONFIG` JSON (protocol v2, "Device configuration"). Every field is nullable and every
 * object optional: a newer or older firmware must never break parsing, and an absent key is
 * "unknown", never a default.
 *
 * ```json
 * {"ble":{"pair":"random","pin_set":1,"pin_default":0,"bonds":1,"display":1},
 *  "wifi":{"on":1,"ssid":"home","psk_set":1,"state":"connected","ip":"192.168.1.42","rssi":-58,"mac":"e0..6b40"},
 *  "lan":{"on":1,"port":47390,"host":"os-6b40","clients":0},
 *  "sensor":{"vendor":"Acme","model":"X1","serial":"X1-0042","batch_candidate":0}}
 * ```
 */
@Serializable
data class DeviceConfig(
    val ble: BleConfig? = null,
    val wifi: WifiConfig? = null,
    val lan: LanConfig? = null,
    val sensor: SensorConfig? = null,
    val location: LocationConfig? = null,
    val thing: ThingConfig? = null,
) {
    companion object {
        fun parse(text: String): DeviceConfig = WireJson.decodeFromString(serializer(), text)
    }
}

/** GET_CONFIG page 1. Coordinates are the H3 cell centre, never the phone fix. */
@Serializable
data class LocationConfig(
    val cell: String? = null,
    val resolution: Int? = null,
    @SerialName("cell_resolution") val cellResolution: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val country: String? = null,
)

/** GET_CONFIG page 1 `thing` object. */
@Serializable
data class ThingConfig(
    val mobility: String? = null,
) {
    val thingMobility: ThingMobility? get() = ThingMobility.fromWire(mobility)
}

/** `thing.mobility`, whether a Thing stays in one H3 cell or moves. The firmware default is [FIXED]. */
enum class ThingMobility(val wire: String) {
    FIXED("fixed"),
    MOVING("moving"),
    ;

    companion object {
        fun fromWire(value: String?): ThingMobility? = entries.firstOrNull { it.wire == value }
    }
}

/** Board-provisioned identity of the replaceable sensor module. Not a calibration certificate. */
@Serializable
data class SensorConfig(
    val vendor: String? = null,
    val model: String? = null,
    val serial: String? = null,
    @SerialName("batch_candidate") val batchCandidate: Int? = null,
)

/** `ble.pair` values. */
enum class PairMode(val wire: String, val label: String) {
    RANDOM("random", "Random passkey (shown on the screen)"),
    FIXED("fixed", "Fixed PIN"),
    NONE("none", "No passkey (Just Works, lab only)");

    companion object {
        fun fromWire(value: String?): PairMode? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class BleConfig(
    /** `random` | `fixed` | `none`. */
    val pair: String? = null,
    /** 1 when a fixed PIN is stored. The firmware never echoes the PIN itself. */
    @SerialName("pin_set") val pinSet: Int? = null,
    /** 1 while the legacy universal PIN is still set, the phone's cue to ask for a new one. */
    @SerialName("pin_default") val pinDefault: Int? = null,
    /** Bonded phones currently stored (max 3). */
    val bonds: Int? = null,
    /** What the device detected at first boot (1 = display present). */
    val display: Int? = null,
) {
    val pairMode: PairMode? get() = PairMode.fromWire(pair)
    val isPinDefault: Boolean get() = pinDefault == 1
    val hasPin: Boolean get() = pinSet == 1
}

/** `wifi.state` values. */
enum class WifiState(val wire: String) {
    OFF("off"), CONNECTING("connecting"), CONNECTED("connected"), FAILED("failed");

    companion object {
        fun fromWire(value: String?): WifiState? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class WifiConfig(
    val on: Int? = null,
    val ssid: String? = null,
    /** Secrets are write-only: the PSK is never echoed, only whether one is stored. */
    @SerialName("psk_set") val pskSet: Int? = null,
    /** `off` | `connecting` | `connected` | `failed`. */
    val state: String? = null,
    val ip: String? = null,
    val rssi: Int? = null,
    val mac: String? = null,
) {
    val isOn: Boolean get() = on == 1
    val hasPsk: Boolean get() = pskSet == 1
    val wifiState: WifiState? get() = WifiState.fromWire(state)
}

@Serializable
data class LanConfig(
    val on: Int? = null,
    val port: Int? = null,
    /** mDNS host label such as `os-6b40`. */
    val host: String? = null,
    /** Authenticated TCP clients right now (0 or 1). */
    val clients: Int? = null,
) {
    val isOn: Boolean get() = on == 1
}

/** Which local check a [ConfigPatch] value failed. Apps map it to their own copy. */
enum class ConfigProblem { PIN_FORMAT, SSID_EMPTY, SSID_TOO_LONG, PSK_TOO_LONG, STICKER_FORMAT, LINE_BREAK }

/** [message] is technical (for logs and tests) and never contains the rejected value. */
class ConfigValidationException(val problem: ConfigProblem, message: String) : IllegalArgumentException(message)

/**
 * Builds the `SET_CONFIG` body: `key=value` lines, UTF-8, `\n`-separated. Partial by contract —
 * only keys present change — and validated here with the same limits the firmware applies, so
 * an obviously bad value never reaches the device.
 */
class ConfigPatch private constructor(private val lines: List<Pair<String, String>>) {

    val isEmpty: Boolean get() = lines.isEmpty()
    val keys: List<String> get() = lines.map { it.first }

    /** Whether the patch touches `ble.*` settings that only apply at the next boot. */
    val changesBle: Boolean get() = lines.any { it.first == KEY_BLE_PAIR || it.first == KEY_BLE_PIN }

    /**
     * Whether the patch carries a secret (`wifi.psk` or `ble.pin`). Such a patch goes only over the
     * Bluetooth link, which is encrypted, never the plaintext LAN socket (#155). Pair mode `none`
     * is encrypted without MITM protection and is lab only per the firmware docs.
     */
    val carriesSecret: Boolean get() = lines.any { it.first in SECRET_KEYS }

    /** The wire text. Each line ends in `\n`; the firmware skips empty lines. */
    fun encode(): String = buildString {
        for ((k, v) in lines) append(k).append('=').append(v).append('\n')
    }

    /** Keys only, for logs: values may be secrets. */
    fun describe(): String = keys.joinToString(",")

    override fun toString(): String = "ConfigPatch(${describe()})"

    class Builder {
        private val lines = ArrayList<Pair<String, String>>()

        fun blePair(mode: PairMode): Builder = add(KEY_BLE_PAIR, mode.wire)

        /** Exactly six ASCII digits, `000000`–`999999`. */
        fun blePin(pin: String): Builder {
            if (pin.length != 6 || !pin.all { it in '0'..'9' }) {
                throw ConfigValidationException(ConfigProblem.PIN_FORMAT, "PIN must be exactly 6 digits")
            }
            return add(KEY_BLE_PIN, pin)
        }

        /** Device location is already coarsened on the phone. No coordinates cross this API. */
        fun location(cell: String, resolution: Int, country: String): Builder {
            require(resolution in 0..15) { "H3 resolution must be 0 to 15" }
            require(cell.isEmpty() || Regex("[0-9a-f]{15}").matches(cell)) { "invalid H3 cell text" }
            require(country.isEmpty() || Regex("[A-Z]{2}").matches(country)) { "country must be an uppercase ISO alpha2 code" }
            add(ConfigFields.LOCATION_CELL, cell)
            add(ConfigFields.LOCATION_RESOLUTION, resolution.toString())
            return add(ConfigFields.LOCATION_COUNTRY, country)
        }

        fun clearBonds(): Builder = add(ConfigFields.BLE_CLEAR_BONDS, "1")

        fun wifiOn(on: Boolean): Builder = add(ConfigFields.WIFI_ON, if (on) "1" else "0")

        /** ≤ 32 bytes UTF-8, no line breaks (the wire format is line-based). */
        fun wifiSsid(ssid: String): Builder {
            val bytes = ssid.encodeToByteArray().size
            if (bytes == 0) throw ConfigValidationException(ConfigProblem.SSID_EMPTY, "SSID is empty")
            if (bytes > SSID_MAX_BYTES) throw ConfigValidationException(ConfigProblem.SSID_TOO_LONG, "SSID is $bytes bytes, max $SSID_MAX_BYTES")
            requireSingleLine(ssid, "SSID")
            return add(ConfigFields.WIFI_SSID, ssid)
        }

        /** ≤ 63 bytes UTF-8; empty for an open network. */
        fun wifiPsk(psk: String): Builder {
            val bytes = psk.encodeToByteArray().size
            if (bytes > PSK_MAX_BYTES) throw ConfigValidationException(ConfigProblem.PSK_TOO_LONG, "passphrase is $bytes bytes, max $PSK_MAX_BYTES")
            requireSingleLine(psk, "passphrase")
            return add(KEY_WIFI_PSK, psk)
        }

        fun lanOn(on: Boolean): Builder = add(ConfigFields.LAN_ON, if (on) "1" else "0")

        fun rotateToken(): Builder = add(ConfigFields.LAN_ROTATE_TOKEN, "1")

        /**
         * The replaceable sensor module's identity. A family checks its own sticker format first and
         * calls this with the checked values.
         */
        fun sensorIdentity(vendor: String, model: String, serial: String, batchCandidate: Boolean): Builder {
            requireSingleLine(vendor, "sensor vendor")
            requireSingleLine(model, "sensor model")
            requireSingleLine(serial, "sensor serial")
            add(ConfigFields.SENSOR_VENDOR, vendor)
            add(ConfigFields.SENSOR_MODEL, model)
            add(ConfigFields.SENSOR_SERIAL, serial)
            return add(ConfigFields.SENSOR_BATCH_CANDIDATE, if (batchCandidate) "1" else "0")
        }

        fun build(): ConfigPatch = ConfigPatch(lines.toList())

        private fun add(key: String, value: String): Builder {
            lines.removeAll { it.first == key }
            lines += key to value
            return this
        }

        private fun requireSingleLine(value: String, what: String) {
            if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                throw ConfigValidationException(ConfigProblem.LINE_BREAK, "$what must not contain line breaks")
            }
        }
    }

    companion object {
        const val KEY_BLE_PAIR = ConfigFields.BLE_PAIR
        const val KEY_BLE_PIN = ConfigFields.BLE_PIN
        const val KEY_WIFI_PSK = ConfigFields.WIFI_PSK

        /**
         * Reserved. The H3 cell hex of the phone's own fix, which the board keeps in RAM and writes
         * with `location_source` 3. No released firmware accepts it yet, so no builder sends it.
         */
        const val KEY_LOCATION_PHONE_CELL = "location.phone_cell"

        /** Keys whose values are secrets, from the [ConfigFields] table. See [carriesSecret]. */
        val SECRET_KEYS: Set<String> get() = ConfigFields.SECRET_KEYS
        const val SSID_MAX_BYTES = 32
        const val PSK_MAX_BYTES = 63

        fun build(block: Builder.() -> Unit): ConfigPatch = Builder().apply(block).build()
    }
}

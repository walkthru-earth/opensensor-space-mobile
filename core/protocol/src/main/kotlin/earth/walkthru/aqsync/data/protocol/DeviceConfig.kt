package earth.walkthru.aqsync.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The `CONFIG` JSON (protocol v2, "Device configuration"). Every field is nullable and every
 * object optional: a newer or older firmware must never break parsing, and an absent key is
 * "unknown", never a default.
 *
 * ```json
 * {"ble":{"pair":"random","pin":123456,"pin_default":1,"bonds":1,"display":1},
 *  "wifi":{"on":1,"ssid":"home","psk_set":1,"state":"connected","ip":"192.168.1.42","rssi":-58,"mac":"e0..6b40"},
 *  "lan":{"on":1,"port":47390,"host":"aq-6b40","clients":0}}
 * ```
 */
@Serializable
data class DeviceConfig(
    val ble: BleConfig? = null,
    val wifi: WifiConfig? = null,
    val lan: LanConfig? = null,
) {
    companion object {
        fun parse(text: String): DeviceConfig = AqJson.decodeFromString(serializer(), text)
    }
}

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
    /** Passkey for `fixed` mode; the firmware emits it as a number, so `012345` arrives as 12345. */
    val pin: Int? = null,
    /** 1 while `ble.pin` is still 123456 — the phone's cue to nag. */
    @SerialName("pin_default") val pinDefault: Int? = null,
    /** Bonded phones currently stored (max 3). */
    val bonds: Int? = null,
    /** What the device detected at first boot (1 = display present). */
    val display: Int? = null,
) {
    val pairMode: PairMode? get() = PairMode.fromWire(pair)
    val isPinDefault: Boolean get() = pinDefault == 1

    /** Six-digit rendering of [pin], restoring leading zeros lost on the wire. */
    val pinText: String? get() = pin?.let { String.format(Locale.ROOT, "%06d", it) }
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
    /** mDNS host label, `aq-xxxx`. */
    val host: String? = null,
    /** Authenticated TCP clients right now (0 or 1). */
    val clients: Int? = null,
) {
    val isOn: Boolean get() = on == 1
}

class ConfigValidationException(message: String) : IllegalArgumentException(message)

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
                throw ConfigValidationException("PIN must be exactly 6 digits")
            }
            return add(KEY_BLE_PIN, pin)
        }

        fun clearBonds(): Builder = add("ble.clear_bonds", "1")

        fun wifiOn(on: Boolean): Builder = add("wifi.on", if (on) "1" else "0")

        /** ≤ 32 bytes UTF-8, no line breaks (the wire format is line-based). */
        fun wifiSsid(ssid: String): Builder {
            val bytes = ssid.toByteArray(Charsets.UTF_8).size
            if (bytes == 0) throw ConfigValidationException("SSID is empty")
            if (bytes > SSID_MAX_BYTES) throw ConfigValidationException("SSID is $bytes bytes; max $SSID_MAX_BYTES")
            requireSingleLine(ssid, "SSID")
            return add("wifi.ssid", ssid)
        }

        /** ≤ 63 bytes UTF-8; empty for an open network. */
        fun wifiPsk(psk: String): Builder {
            val bytes = psk.toByteArray(Charsets.UTF_8).size
            if (bytes > PSK_MAX_BYTES) throw ConfigValidationException("passphrase is $bytes bytes; max $PSK_MAX_BYTES")
            requireSingleLine(psk, "passphrase")
            return add("wifi.psk", psk)
        }

        fun lanOn(on: Boolean): Builder = add("lan.on", if (on) "1" else "0")

        fun rotateToken(): Builder = add("lan.rotate_token", "1")

        fun build(): ConfigPatch = ConfigPatch(lines.toList())

        private fun add(key: String, value: String): Builder {
            lines.removeAll { it.first == key }
            lines += key to value
            return this
        }

        private fun requireSingleLine(value: String, what: String) {
            if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
                throw ConfigValidationException("$what must not contain line breaks")
            }
        }
    }

    companion object {
        const val KEY_BLE_PAIR = "ble.pair"
        const val KEY_BLE_PIN = "ble.pin"
        const val SSID_MAX_BYTES = 32
        const val PSK_MAX_BYTES = 63

        /** The PIN every `fixed`-mode device ships with; `pin_default` reports whether it is still set. */
        const val DEFAULT_PIN = "123456"

        fun build(block: Builder.() -> Unit): ConfigPatch = Builder().apply(block).build()
    }
}

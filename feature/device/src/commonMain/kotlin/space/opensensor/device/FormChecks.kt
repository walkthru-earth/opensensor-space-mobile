package space.opensensor.device

import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.family.air.SensorLabel

// Field checks for the Device settings forms. Each returns the reason a typed value cannot be
// saved, or null when it can, so the field shows the reason in words beside the disabled Save.
// None of them logs or keeps the value.

/** Why a typed Wi-Fi passphrase cannot be saved. WPA2 counts bytes, not letters. */
enum class PskProblem { SHORT, LONG }

/** Smallest WPA2 passphrase, in UTF-8 bytes. */
const val PSK_MIN_BYTES = 8

/** Null when [psk] is empty (nothing typed yet) or fits [PSK_MIN_BYTES] to [ConfigPatch.PSK_MAX_BYTES]. */
fun pskProblem(psk: String): PskProblem? {
    if (psk.isEmpty()) return null
    val bytes = psk.encodeToByteArray().size
    return when {
        bytes < PSK_MIN_BYTES -> PskProblem.SHORT
        bytes > ConfigPatch.PSK_MAX_BYTES -> PskProblem.LONG
        else -> null
    }
}

/** True when a secured network's passphrase can be sent. */
fun pskSavable(psk: String): Boolean = psk.isNotEmpty() && pskProblem(psk) == null

/** Why a typed sensor sticker cannot be saved. */
enum class StickerProblem {
    /** Not a sticker this app can read. */
    FORMAT,

    /** A real sticker for a model this board does not run. */
    OTHER_MODEL,
}

/** Null when [serial] is blank or names [supportedModel]. */
fun stickerProblem(serial: String, supportedModel: String = DeviceConfigController.SUPPORTED_MODEL): StickerProblem? {
    if (serial.isBlank()) return null
    val label = SensorLabel.parse(serial) ?: return StickerProblem.FORMAT
    return if (label.model == supportedModel) null else StickerProblem.OTHER_MODEL
}

private val COUNTRY_CODE = Regex("[A-Z]{2}")

/** The optional country code is empty or two capital letters A to Z. */
fun countryValid(country: String): Boolean = country.isEmpty() || COUNTRY_CODE.matches(country)

package space.opensensor.device

import space.opensensor.family.air.SensorLabel

/**
 * Turns one platform barcode read into the [StickerScan] the sticker card shows. Android's Google
 * code scanner and the iOS VisionKit scanner both call it, so a scanned value goes through the same
 * [SensorLabel] parser as typed entry, and the card and [DeviceConfigController.saveSensor] then apply
 * the same model check. Only the decoded string arrives here, never a camera frame.
 */
object StickerScans {
    /**
     * The card's next scan after the scanner answered [raw]. Null is a cancel and returns [previous]
     * unchanged, so the field keeps what the user typed. A value the parser does not know becomes
     * [unrecognized] with no value. Every new result takes the next id, so scanning the same sticker
     * twice still fills the field again.
     */
    fun read(previous: StickerScan?, raw: String?, unrecognized: String): StickerScan? {
        if (raw == null) return previous
        val value = raw.trim()
        return if (SensorLabel.parse(value) == null) {
            StickerScan(null, unrecognized, nextId(previous))
        } else {
            StickerScan(value, null, nextId(previous))
        }
    }

    /** The scanner could not run or the camera is off. [message] shows on the card, manual entry stays. */
    fun failed(previous: StickerScan?, message: String): StickerScan = StickerScan(null, message, nextId(previous))

    private fun nextId(previous: StickerScan?): Long = (previous?.id ?: 0L) + 1
}

package earth.walkthru.aqsync.data

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The sticker belongs to the replaceable PM module, not to the ESP32 board. */
data class SensorLabel(val vendor: String, val model: String, val serial: String, val batch: String) {
    companion object {
        private val plantower = Regex("^(PMS5003T|PMS5003|PMSA003)-([0-9]{8})([0-9]{1,9})$", RegexOption.IGNORE_CASE)

        fun parse(value: String): SensorLabel? {
            val normalized = value.trim().uppercase(Locale.ROOT)
            val match = plantower.matchEntire(normalized) ?: return null
            try {
                if (LocalDate.parse(match.groupValues[2], DateTimeFormatter.BASIC_ISO_DATE).year < 2000) return null
            } catch (_: DateTimeException) {
                return null
            }
            return SensorLabel("Plantower", match.groupValues[1], normalized, "${match.groupValues[1]}-${match.groupValues[2]}")
        }
    }
}

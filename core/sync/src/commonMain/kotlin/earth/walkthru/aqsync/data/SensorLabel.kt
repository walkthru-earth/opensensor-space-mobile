package earth.walkthru.aqsync.data

/** The sticker belongs to the replaceable PM module, not to the ESP32 board. */
data class SensorLabel(val vendor: String, val model: String, val serial: String, val batch: String) {
    companion object {
        private val plantower = Regex("^(PMS5003T|PMS5003|PMSA003)-([0-9]{8})([0-9]{1,9})$", RegexOption.IGNORE_CASE)

        fun parse(value: String): SensorLabel? {
            val normalized = value.trim().uppercase()
            val match = plantower.matchEntire(normalized) ?: return null
            if (!isBasicIsoDate(match.groupValues[2])) return null
            return SensorLabel("Plantower", match.groupValues[1], normalized, "${match.groupValues[1]}-${match.groupValues[2]}")
        }

        /** `yyyyMMdd`, a real calendar day in the year 2000 or later. */
        private fun isBasicIsoDate(digits: String): Boolean {
            val year = digits.substring(0, 4).toInt()
            val month = digits.substring(4, 6).toInt()
            val day = digits.substring(6, 8).toInt()
            if (year < 2000 || month !in 1..12 || day < 1) return false
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            val days = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            return day <= days
        }
    }
}

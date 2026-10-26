package space.opensensor.model

/**
 * What LIVE shows for one template and profile. [keys] are the LIVE keys of every column the profile
 * does not hide. [temperature] is the first `Cel` column with a LIVE key, a `physicalMeasurement`
 * before any other role, so an air temperature wins over a board temperature. [humidity] is the first
 * `physicalMeasurement` column in `%` with a LIVE key. Both follow from the template and profile, never
 * from a key name.
 */
class LiveColumns private constructor(
    val keys: Set<String>,
    val temperature: Datastream?,
    val humidity: Datastream?,
) {
    companion object {
        private const val CELSIUS = "Cel"
        private const val PERCENT = "%"

        fun of(template: BoardTemplate, profile: AppProfile): LiveColumns {
            val shown = template.datastreams.filter { it.liveKey != null && it.name !in profile.hidden }
            val celsius = shown.filter { it.unit == CELSIUS }
            return LiveColumns(
                keys = shown.mapNotNull { it.liveKey }.toSet(),
                temperature = celsius.firstOrNull { it.role == Role.PHYSICAL_MEASUREMENT } ?: celsius.firstOrNull(),
                humidity = shown.firstOrNull { it.unit == PERCENT && it.role == Role.PHYSICAL_MEASUREMENT },
            )
        }
    }
}

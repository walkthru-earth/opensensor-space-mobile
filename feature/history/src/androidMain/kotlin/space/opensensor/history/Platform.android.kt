package space.opensensor.history

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatEpoch(epochS: Long, zone: TimeZone, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(zone.toJavaZoneId()).format(Instant.ofEpochSecond(epochS))

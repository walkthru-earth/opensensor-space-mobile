package space.opensensor.today

import kotlinx.datetime.TimeZone

/**
 * [epochS] in [zone] with a CLDR date [pattern] ("HH:mm", "EEE d MMM", "MMM yyyy", "HH:mm XXX") in
 * the phone's locale, so month and weekday names follow the app language. Android formats with
 * `java.time`, iOS with `NSDateFormatter`. Both read the same CLDR pattern letters.
 */
internal expect fun formatEpoch(epochS: Long, zone: TimeZone, pattern: String): String

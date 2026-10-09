package earth.walkthru.opensensor.today

import kotlinx.datetime.TimeZone
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName

internal actual fun formatEpoch(epochS: Long, zone: TimeZone, pattern: String): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.currentLocale
    formatter.dateFormat = pattern
    NSTimeZone.timeZoneWithName(zone.id)?.let { formatter.timeZone = it }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochS.toDouble()))
}

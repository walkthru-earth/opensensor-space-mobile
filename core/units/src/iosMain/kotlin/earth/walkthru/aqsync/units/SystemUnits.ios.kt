package earth.walkthru.aqsync.units

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLocaleMeasurementSystem
import platform.Foundation.currentLocale

/**
 * The iOS measurement system ("Metric", "U.S." or "U.K."), which follows the user's Language and
 * Region setting. "U.K." keeps Celsius and hectopascals, so only "U.S." is imperial. Falls back to
 * the region when the key is missing.
 */
actual fun systemUnitSystem(): UnitSystem {
    val locale = NSLocale.currentLocale
    return when (locale.objectForKey(NSLocaleMeasurementSystem) as? String) {
        "U.S." -> UnitSystem.IMPERIAL
        null -> UnitSystem.forRegion(locale.objectForKey(NSLocaleCountryCode) as? String)
        else -> UnitSystem.METRIC
    }
}

package earth.walkthru.aqsync.ui.components

import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.compose.runtime.staticCompositionLocalOf
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem

/**
 * The display units for every reading, provided once by [earth.walkthru.aqsync.ui.MainActivity]
 * from the Settings choice. Conversion happens in `:core:units`. Stored data stays SI.
 */
val LocalUnits = staticCompositionLocalOf { UnitFormatter.Metric }

/**
 * The Settings choice resolved to METRIC or IMPERIAL. Automatic follows ICU's measurement system for
 * the formatting locale (US for the United States, Liberia and Myanmar, and honours a `-u-ms-`
 * override), falling back to the region code.
 */
fun resolveUnits(choice: UnitSystem): UnitSystem {
    if (choice != UnitSystem.AUTO) return choice
    val locale = ULocale.getDefault(ULocale.Category.FORMAT)
    return runCatching { if (LocaleData.getMeasurementSystem(locale) == LocaleData.MeasurementSystem.US) UnitSystem.IMPERIAL else UnitSystem.METRIC }
        .getOrElse { UnitSystem.forRegion(locale.country) }
}

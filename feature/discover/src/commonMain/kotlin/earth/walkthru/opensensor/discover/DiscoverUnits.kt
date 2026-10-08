package earth.walkthru.opensensor.discover

import androidx.compose.runtime.staticCompositionLocalOf
import earth.walkthru.aqsync.units.UnitFormatter

/**
 * The display units for every value and unit label under [DiscoverScreen], provided once from the
 * host's resolved Settings choice. Metric when nothing is provided (previews, tests).
 */
internal val LocalDiscoverUnits = staticCompositionLocalOf { UnitFormatter.Metric }

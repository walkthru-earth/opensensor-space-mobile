package earth.walkthru.aqsync.units

import java.util.Locale

/** The region of the formatting locale. On Android the app prefers ICU's measurement system. */
actual fun systemUnitSystem(): UnitSystem = UnitSystem.forRegion(Locale.getDefault(Locale.Category.FORMAT).country)

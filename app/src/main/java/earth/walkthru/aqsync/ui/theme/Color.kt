package earth.walkthru.aqsync.ui.theme

import androidx.compose.ui.graphics.Color
import earth.walkthru.aqsync.data.history.AirMood

// Fallback seed palette when dynamic color is unavailable (it always is on API 31+, but a
// custom scheme keeps previews and tests deterministic). Ink, teal, and a warm accent for warnings.
val Ink = Color(0xFF0F1B1E)
val InkSoft = Color(0xFF1A2A2F)
val Teal = Color(0xFF3CC9B0)
val TealDeep = Color(0xFF0E7C6B)
val Mist = Color(0xFFDDEDEA)
val Sand = Color(0xFFF2E5C8)
val Ember = Color(0xFFE8734A)
val Rose = Color(0xFFD64A5C)

// Air-quality bands for PM2.5 in µg/m³ (WHO 2021 24-h guideline is 15; these are display bands,
// not an AQI: no averaging window is applied to a 10-second snapshot). The edges themselves live in
// `AirMood` (:core:history) — never repeat them here.
val BandGood = Color(0xFF4CC38A)
val BandModerate = Color(0xFFE9C46A)
val BandElevated = Color(0xFFF4A261)
val BandHigh = Color(0xFFE76F51)
val BandVeryHigh = Color(0xFF9B2226)

/**
 * The one mapping from a mood to its colour. Every PM2.5 colour in the app goes through
 * `AirMood.of(value).color()`, so a value can never be titled one mood and painted another
 * (a second copy of the edges in `Theme.kt` once used `<=` where `AirMood.of` uses `<`).
 */
fun AirMood.color(): Color = when (this) {
    AirMood.FRESH -> BandGood
    AirMood.FINE -> BandModerate
    AirMood.HAZY -> BandElevated
    AirMood.SMOKY -> BandHigh
    AirMood.THICK -> BandVeryHigh
}

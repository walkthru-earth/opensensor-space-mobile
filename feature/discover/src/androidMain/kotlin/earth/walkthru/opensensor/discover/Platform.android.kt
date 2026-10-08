package earth.walkthru.opensensor.discover

import java.util.TimeZone

internal actual fun currentTimeZoneId(): String = TimeZone.getDefault().id

internal actual fun nowEpochMillis(): Long = System.currentTimeMillis()

// The Android build links ICU bidi and Arabic shaping.
internal actual val mapShapesComplexText: Boolean = true

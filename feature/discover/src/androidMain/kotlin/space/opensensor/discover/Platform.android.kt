package space.opensensor.discover

import java.util.TimeZone

internal actual fun currentTimeZoneId(): String = TimeZone.getDefault().id

internal actual fun nowEpochMillis(): Long = System.currentTimeMillis()

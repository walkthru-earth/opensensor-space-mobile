package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus

enum class ClockQuality { HOST_ANCHORED, RTC_RESTORED, MISSING, UNKNOWN }

enum class ClockSyncAction { CONTINUE, SET_TIME }

data class ClockSyncDecision(
    val quality: ClockQuality,
    val action: ClockSyncAction,
    val reason: String,
)

/**
 * Pure policy for the only state-changing part of Smart Sync. The engine sends phone UTC whenever
 * this returns [ClockSyncAction.SET_TIME], with no user setting. File transfer stays immutable, and
 * FLUSH stays an explicit confirmed action.
 */
object SmartSyncPolicy {
    /** A fresh snapshot is compared at the request midpoint; [uncertaintyMillis] bounds link delay. */
    fun decide(
        status: DeviceStatus?,
        nowEpochMillis: Long? = null,
        uncertaintyMillis: Long = 0,
    ): ClockSyncDecision {
        val quality = quality(status)
        val ageSeconds = status?.clockAgeSeconds
        val deviceUtcMillis = status?.utcEpochMillis
        val reason = when {
            quality == ClockQuality.MISSING -> "device has no UTC anchor, new rows go to the unsynced tree"
            quality == ClockQuality.RTC_RESTORED -> "RTC clock needs an external anchor this boot"
            quality == ClockQuality.UNKNOWN -> "clock state unavailable, obtain an acknowledged phone anchor"
            ageSeconds != null && ageSeconds >= REFRESH_AGE_SECONDS -> "external anchor is at least six hours old"
            nowEpochMillis != null && deviceUtcMillis != null &&
                (
                    deviceUtcMillis > nowEpochMillis + MAX_DRIFT_MILLIS + uncertaintyMillis.coerceAtLeast(0) ||
                        deviceUtcMillis < nowEpochMillis - MAX_DRIFT_MILLIS - uncertaintyMillis.coerceAtLeast(0)
                    ) ->
                "clock differs from the phone by more than two seconds plus link uncertainty"
            else -> null
        }
        return ClockSyncDecision(
            quality,
            if (reason == null) ClockSyncAction.CONTINUE else ClockSyncAction.SET_TIME,
            reason ?: "clock is anchored; no refresh required",
        )
    }

    // At the archive's 50 ppm drift assumption, six hours contributes about 1.08 seconds.
    const val REFRESH_AGE_SECONDS = 6 * 60 * 60L
    const val MAX_DRIFT_MILLIS = 2_000L

    fun quality(status: DeviceStatus?): ClockQuality = when {
        status == null -> ClockQuality.UNKNOWN
        status.utc == 0 || status.clockSource == 0 -> ClockQuality.MISSING
        status.clockSource == 1 || status.clockSource == 3 -> ClockQuality.HOST_ANCHORED
        status.clockSource == 2 -> ClockQuality.RTC_RESTORED
        status.clockSource == null && status.utc == 1 -> ClockQuality.HOST_ANCHORED
        else -> ClockQuality.UNKNOWN
    }
}

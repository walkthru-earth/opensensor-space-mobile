package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus

/** User consent for the state-changing SET_TIME step of Smart Sync. */
enum class ClockSyncMode {
    /** Detect clock problems, but ask before sending this phone's time. */
    ASK,

    /** With no UTC or an RTC-restored clock, send this phone's time before downloading. */
    AUTOMATIC,

    /** Only download files. The app still warns when readings cannot be placed on the timeline. */
    NEVER,
}

enum class ClockQuality { HOST_ANCHORED, RTC_RESTORED, MISSING, UNKNOWN }

enum class ClockSyncAction { CONTINUE, SET_TIME, ASK_USER }

data class ClockSyncDecision(
    val quality: ClockQuality,
    val action: ClockSyncAction,
    val reason: String,
)

/**
 * Pure policy for the only state-changing part of Smart Sync. File transfer remains automatic and
 * immutable; FLUSH is deliberately excluded and stays an explicit "sync through now" action.
 */
object SmartSyncPolicy {
    /** A fresh snapshot is compared at the request midpoint; [uncertaintyMillis] bounds link delay. */
    fun decide(
        status: DeviceStatus?,
        mode: ClockSyncMode,
        nowEpochMillis: Long? = null,
        uncertaintyMillis: Long = 0,
    ): ClockSyncDecision {
        val quality = quality(status)
        val ageSeconds = status?.clockAgeSeconds
        val deviceUtcMillis = status?.utcEpochMillis
        val reason = when {
            mode == ClockSyncMode.NEVER -> null
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
            if (reason == null) {
                ClockSyncAction.CONTINUE
            } else if (mode == ClockSyncMode.AUTOMATIC) {
                ClockSyncAction.SET_TIME
            } else {
                ClockSyncAction.ASK_USER
            },
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

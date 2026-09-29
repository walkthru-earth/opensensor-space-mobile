package earth.walkthru.aqsync.data.sync

import earth.walkthru.aqsync.data.protocol.DeviceStatus

/** User consent for the state-changing SET_TIME step of Smart Sync. */
enum class ClockSyncMode(val title: String, val description: String) {
    ASK(
        "Ask first",
        "Detect clock problems automatically, but ask before sending this phone's time.",
    ),
    AUTOMATIC(
        "Automatic",
        "When Smart Sync finds no UTC or an RTC-restored clock, send this phone's time before downloading.",
    ),
    NEVER(
        "Never",
        "Only download files. The app will still warn when readings cannot be placed on the timeline.",
    ),
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
    fun decide(status: DeviceStatus?, mode: ClockSyncMode): ClockSyncDecision {
        val quality = quality(status)
        if (quality == ClockQuality.HOST_ANCHORED) {
            return ClockSyncDecision(quality, ClockSyncAction.CONTINUE, "clock already anchored by a host")
        }
        if (quality == ClockQuality.UNKNOWN) {
            return ClockSyncDecision(quality, ClockSyncAction.CONTINUE, "firmware did not report enough clock state")
        }
        if (mode == ClockSyncMode.NEVER) {
            return ClockSyncDecision(quality, ClockSyncAction.CONTINUE, "automatic clock updates are disabled")
        }
        val reason = when (quality) {
            ClockQuality.MISSING -> "device has no UTC anchor; new rows are going to the unsynced tree"
            ClockQuality.RTC_RESTORED -> "device clock was restored from the RTC and has not been checked by this phone"
            ClockQuality.HOST_ANCHORED, ClockQuality.UNKNOWN -> error("handled above")
        }
        return ClockSyncDecision(
            quality = quality,
            action = if (mode == ClockSyncMode.AUTOMATIC) ClockSyncAction.SET_TIME else ClockSyncAction.ASK_USER,
            reason = reason,
        )
    }

    fun quality(status: DeviceStatus?): ClockQuality = when {
        status == null -> ClockQuality.UNKNOWN
        status.clockSource == 1 -> ClockQuality.HOST_ANCHORED
        status.clockSource == 2 -> ClockQuality.RTC_RESTORED
        status.clockSource == 0 || status.utc == 0 -> ClockQuality.MISSING
        status.clockSource == null && status.utc == 1 -> ClockQuality.HOST_ANCHORED
        else -> ClockQuality.UNKNOWN
    }
}

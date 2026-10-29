package space.opensensor.data.sync

import space.opensensor.data.protocol.DeviceStatus
import kotlin.math.abs

enum class ClockQuality { HOST_ANCHORED, RTC_RESTORED, MISSING, UNKNOWN }

enum class ClockSyncAction { CONTINUE, SET_TIME }

data class ClockSyncDecision(
    val quality: ClockQuality,
    val action: ClockSyncAction,
    val reason: String,
)

/**
 * Pure policy for the only automatic clock change. Android and iOS run it at most once per
 * connection and send phone UTC whenever it returns [ClockSyncAction.SET_TIME]. There is no user
 * setting.
 *
 * The phone fills a missing or RTC-only anchor and refreshes an old one. It does not replace a
 * recent anchor from another source unless the two differ by more than a minute, so two phones
 * cannot flip-flop the clock. It never replaces a network (SNTP) anchor by a large difference,
 * since a hand-set phone is then the likelier fault. A phone earlier than
 * [PHONE_CLOCK_FLOOR_MILLIS] never sends its time.
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
        val uncertainty = uncertaintyMillis.coerceAtLeast(0)
        val drift = if (nowEpochMillis != null && deviceUtcMillis != null) abs(deviceUtcMillis - nowEpochMillis) else null
        val stale = ageSeconds != null && ageSeconds >= REFRESH_AGE_SECONDS
        val fresh = ageSeconds != null && ageSeconds < REFRESH_AGE_SECONDS
        // Oscillator drift expected since the anchor, at the archive's 50 ppm assumption.
        val agedDrift = (ageSeconds ?: 0L).coerceAtLeast(0) * DRIFT_PPM / 1_000
        val (set, reason) = when {
            nowEpochMillis != null && nowEpochMillis < PHONE_CLOCK_FLOOR_MILLIS ->
                false to "phone clock is earlier than this build, so it is not sent"
            quality == ClockQuality.MISSING -> true to "device has no UTC anchor, new rows go to the unsynced tree"
            quality == ClockQuality.RTC_RESTORED -> true to "RTC clock needs an external anchor this boot"
            quality == ClockQuality.UNKNOWN -> true to "clock state unavailable, obtain an acknowledged phone anchor"
            drift == null -> if (stale) true to AGED else false to KEEP
            drift > LARGE_DRIFT_MILLIS + agedDrift + uncertainty ->
                if (status?.clockSource == NETWORK_SOURCE) {
                    false to "network anchor differs from the phone by over a minute, the phone is the likelier fault"
                } else {
                    true to "clock differs from the phone by more than a minute"
                }
            drift > MAX_DRIFT_MILLIS + uncertainty ->
                if (fresh) {
                    false to "recent anchor from another source is within a minute, kept to avoid flip-flop"
                } else {
                    true to "clock differs from the phone by more than two seconds plus link uncertainty"
                }
            stale -> true to AGED
            else -> false to KEEP
        }
        return ClockSyncDecision(quality, if (set) ClockSyncAction.SET_TIME else ClockSyncAction.CONTINUE, reason)
    }

    private const val KEEP = "clock is anchored; no refresh required"
    private const val AGED = "external anchor is at least six hours old"
    private const val NETWORK_SOURCE = 3

    // At the archive's 50 ppm drift assumption, six hours contributes about 1.08 seconds.
    const val REFRESH_AGE_SECONDS = 6 * 60 * 60L
    const val MAX_DRIFT_MILLIS = 2_000L
    const val DRIFT_PPM = 50L

    /**
     * Two clocks that follow network time agree within about a second, and a clock set by hand is
     * set to the minute. A difference beyond a minute, plus expected drift and link delay, means one
     * side was set by hand. Below it, a recent anchor from another source is kept.
     */
    const val LARGE_DRIFT_MILLIS = 60_000L

    /** 2026-10-29T00:00:00Z, the day this rule was built. A phone earlier than that has a wrong clock. */
    const val PHONE_CLOCK_FLOOR_MILLIS = 1_793_232_000_000L

    fun quality(status: DeviceStatus?): ClockQuality = when {
        status == null -> ClockQuality.UNKNOWN
        status.utc == 0 || status.clockSource == 0 -> ClockQuality.MISSING
        status.clockSource == 1 || status.clockSource == 3 -> ClockQuality.HOST_ANCHORED
        status.clockSource == 2 -> ClockQuality.RTC_RESTORED
        status.clockSource == null && status.utc == 1 -> ClockQuality.HOST_ANCHORED
        else -> ClockQuality.UNKNOWN
    }
}

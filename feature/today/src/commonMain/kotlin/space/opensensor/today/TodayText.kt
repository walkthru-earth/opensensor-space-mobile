package space.opensensor.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.sync.ClockQuality
import space.opensensor.designsystem.moodLineRes
import space.opensensor.designsystem.moodWord
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.PmsStatus
import space.opensensor.family.air.formatPm
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.ago_days
import space.opensensor.today.resources.ago_hours
import space.opensensor.today.resources.ago_just_now
import space.opensensor.today.resources.ago_minutes
import space.opensensor.today.resources.ago_seconds
import space.opensensor.today.resources.clock_reason_missing
import space.opensensor.today.resources.clock_reason_rtc
import space.opensensor.today.resources.clock_reason_unknown
import space.opensensor.today.resources.conn_connecting
import space.opensensor.today.resources.conn_disconnected
import space.opensensor.today.resources.conn_discovering
import space.opensensor.today.resources.conn_failed
import space.opensensor.today.resources.conn_lan_auth
import space.opensensor.today.resources.conn_lan_checking
import space.opensensor.today.resources.conn_mtu
import space.opensensor.today.resources.conn_notifications
import space.opensensor.today.resources.conn_pairing
import space.opensensor.today.resources.conn_pairing_repair
import space.opensensor.today.resources.conn_ready
import space.opensensor.today.resources.conn_ready_mtu
import space.opensensor.today.resources.conn_setting_up
import space.opensensor.today.resources.info_now_body
import space.opensensor.today.resources.info_now_body_simple
import space.opensensor.today.resources.mood_fair_advice
import space.opensensor.today.resources.mood_fair_like
import space.opensensor.today.resources.mood_fresh_advice
import space.opensensor.today.resources.mood_fresh_like
import space.opensensor.today.resources.mood_hazy_advice
import space.opensensor.today.resources.mood_hazy_like
import space.opensensor.today.resources.mood_poor_advice
import space.opensensor.today.resources.mood_poor_like
import space.opensensor.today.resources.mood_smoky_advice
import space.opensensor.today.resources.mood_smoky_like
import space.opensensor.today.resources.mood_thick_advice
import space.opensensor.today.resources.mood_thick_like
import space.opensensor.today.resources.pms_absent_hint
import space.opensensor.today.resources.pms_absent_title
import space.opensensor.today.resources.pms_error_hint
import space.opensensor.today.resources.pms_error_title
import space.opensensor.today.resources.pms_mismatch_hint
import space.opensensor.today.resources.pms_mismatch_title
import space.opensensor.today.resources.pms_sleeping_hint
import space.opensensor.today.resources.pms_sleeping_title
import space.opensensor.today.resources.pms_stale_hint
import space.opensensor.today.resources.pms_stale_title
import space.opensensor.today.resources.pms_valid_title
import space.opensensor.today.resources.pms_warming_hint
import space.opensensor.today.resources.pms_warming_title
import space.opensensor.today.resources.transport_ble
import space.opensensor.today.resources.transport_fake
import space.opensensor.today.resources.transport_lan
import space.opensensor.units.formatFixed

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
internal const val NO_VALUE = "n/a"

/** A reading older than this is shown as stale (the device samples every 10 s). */
internal const val STALE_AFTER_MS = 30_000L

/** Readings print without a trailing `.0` and with one decimal otherwise (the app's `fmt`). */
internal fun fmt(v: Double?): String = when {
    v == null -> NO_VALUE
    v == kotlin.math.floor(v) && v < 1000 -> formatFixed(v, 0)
    else -> formatFixed(v, 1)
}

/** PM values, the shared rule both apps use ([formatPm] in `:core:units`). */
internal fun fmtPm(v: Double): String = formatPm(v)

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${formatFixed(bytes / 1024.0, 1)} KiB"
    bytes < 1024L * 1024 * 1024 -> "${formatFixed(bytes / 1048576.0, 2)} MiB"
    else -> "${formatFixed(bytes / 1073741824.0, 2)} GiB"
}

internal fun formatUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m ${seconds % 60}s"
    }
}

/** Which "ago" string an age uses and the number it carries, a pure rule for tests. */
internal fun agoParts(ageMs: Long): Pair<AgoUnit, Long> {
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> AgoUnit.JUST_NOW to 0L
        s < 60 -> AgoUnit.SECONDS to s
        s < 3600 -> AgoUnit.MINUTES to s / 60
        s < 86_400 -> AgoUnit.HOURS to s / 3600
        else -> AgoUnit.DAYS to s / 86_400
    }
}

internal enum class AgoUnit { JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }

/** Fills the first `%1$s` or `%1$d` of a template read once, for leaf lambdas that tick each second. */
internal fun fill(template: String, value: String): String =
    template.replace("%1\$s", value).replace("%1\$d", value)

/**
 * "12 s ago" as a plain function bound to the current language, for `TickingText` lambdas. The
 * templates are read once per composition, the lambda only fills in the number.
 */
@Composable
internal fun agoFormatter(): (Long) -> String {
    val justNow = stringResource(Res.string.ago_just_now)
    val seconds = stringResource(Res.string.ago_seconds)
    val minutes = stringResource(Res.string.ago_minutes)
    val hours = stringResource(Res.string.ago_hours)
    val days = stringResource(Res.string.ago_days)
    return remember(justNow, seconds, minutes, hours, days) {
        { ageMs ->
            val (unit, n) = agoParts(ageMs)
            when (unit) {
                AgoUnit.JUST_NOW -> justNow
                AgoUnit.SECONDS -> fill(seconds, n.toString())
                AgoUnit.MINUTES -> fill(minutes, n.toString())
                AgoUnit.HOURS -> fill(hours, n.toString())
                AgoUnit.DAYS -> fill(days, n.toString())
            }
        }
    }
}

@Composable
internal fun AirMood.titleText(): String = moodWord(step)

/**
 * The hero's line under the mood. Detailed reads advice that names the WHO guideline. Simple speaks
 * in moods only, so it reads the shared mood line.
 */
internal fun heroLineRes(mood: AirMood, level: DetailLevel): StringResource =
    if (level.detailed) mood.adviceRes() else moodLineRes(mood.step)

/** The hero's info sheet body. Only the detailed levels name the WHO step. */
internal fun nowInfoBodyRes(level: DetailLevel): StringResource =
    if (level.detailed) Res.string.info_now_body else Res.string.info_now_body_simple

private fun AirMood.adviceRes(): StringResource = when (this) {
    AirMood.FRESH -> Res.string.mood_fresh_advice
    AirMood.FAIR -> Res.string.mood_fair_advice
    AirMood.HAZY -> Res.string.mood_hazy_advice
    AirMood.POOR -> Res.string.mood_poor_advice
    AirMood.SMOKY -> Res.string.mood_smoky_advice
    AirMood.THICK -> Res.string.mood_thick_advice
}

@Composable
internal fun AirMood.comparisonText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> Res.string.mood_fresh_like
        AirMood.FAIR -> Res.string.mood_fair_like
        AirMood.HAZY -> Res.string.mood_hazy_like
        AirMood.POOR -> Res.string.mood_poor_like
        AirMood.SMOKY -> Res.string.mood_smoky_like
        AirMood.THICK -> Res.string.mood_thick_like
    },
)

@Composable
internal fun PmsStatus.titleText(): String = stringResource(
    when (this) {
        PmsStatus.ABSENT -> Res.string.pms_absent_title
        PmsStatus.WARMING -> Res.string.pms_warming_title
        PmsStatus.STALE -> Res.string.pms_stale_title
        PmsStatus.ERROR -> Res.string.pms_error_title
        PmsStatus.MODEL_MISMATCH -> Res.string.pms_mismatch_title
        PmsStatus.SLEEPING -> Res.string.pms_sleeping_title
        PmsStatus.VALID -> Res.string.pms_valid_title
    },
)

@Composable
internal fun PmsStatus.hintText(): String = stringResource(
    when (this) {
        PmsStatus.ABSENT -> Res.string.pms_absent_hint
        PmsStatus.WARMING -> Res.string.pms_warming_hint
        PmsStatus.STALE -> Res.string.pms_stale_hint
        PmsStatus.ERROR -> Res.string.pms_error_hint
        PmsStatus.MODEL_MISMATCH -> Res.string.pms_mismatch_hint
        PmsStatus.SLEEPING -> Res.string.pms_sleeping_hint
        PmsStatus.VALID -> Res.string.pms_valid_title
    },
)

/** Why Smart Sync wants to set the clock, in words (the app's `ClockQuality.reasonRes`). */
internal fun ClockQuality.reasonRes(): StringResource = when (this) {
    ClockQuality.MISSING -> Res.string.clock_reason_missing
    ClockQuality.RTC_RESTORED -> Res.string.clock_reason_rtc
    ClockQuality.HOST_ANCHORED, ClockQuality.UNKNOWN -> Res.string.clock_reason_unknown
}

/** The name of a byte pipe, for chips and stat lines. */
@Composable
fun TransportKind.labelText(): String = stringResource(
    when (this) {
        TransportKind.BLE -> Res.string.transport_ble
        TransportKind.LAN -> Res.string.transport_lan
        TransportKind.FAKE -> Res.string.transport_fake
    },
)

/**
 * The link state in words. Link internals such as the MTU appear only when [technical] is set
 * (Developer level). [pairingRepair] comes from the platform, which knows its own failure codes.
 */
@Composable
fun ConnectionState.describe(technical: Boolean, pairingRepair: Boolean = false): String = when (this) {
    ConnectionState.Disconnected -> stringResource(Res.string.conn_disconnected)
    ConnectionState.Connecting -> stringResource(Res.string.conn_connecting)
    ConnectionState.DiscoveringServices -> stringResource(if (technical) Res.string.conn_discovering else Res.string.conn_connecting)
    ConnectionState.Bonding -> stringResource(Res.string.conn_pairing)
    ConnectionState.NegotiatingMtu -> stringResource(if (technical) Res.string.conn_mtu else Res.string.conn_setting_up)
    ConnectionState.EnablingNotifications -> stringResource(if (technical) Res.string.conn_notifications else Res.string.conn_setting_up)
    ConnectionState.Authenticating -> stringResource(if (technical) Res.string.conn_lan_auth else Res.string.conn_lan_checking)
    // iOS reports Ready without a negotiated MTU (0), so the technical line only names it when known.
    is ConnectionState.Ready -> if (technical && mtu > 0) stringResource(Res.string.conn_ready_mtu, mtu) else stringResource(Res.string.conn_ready)
    // A pairing repair message is plain language and says what to do, so every level shows it.
    is ConnectionState.Failed -> when {
        pairingRepair -> stringResource(Res.string.conn_pairing_repair)
        technical -> reason
        else -> stringResource(Res.string.conn_failed)
    }
}

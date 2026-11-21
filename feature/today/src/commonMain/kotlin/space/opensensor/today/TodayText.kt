package space.opensensor.today

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.designsystem.moodLineRes
import space.opensensor.designsystem.moodWord
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.Averaging
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.formatPm
import space.opensensor.model.SensorState
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
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
import space.opensensor.today.resources.now_basis_live
import space.opensensor.today.resources.now_basis_nowcast
import space.opensensor.today.resources.now_basis_nowcast_simple
import space.opensensor.today.resources.now_nowcast_note
import space.opensensor.today.resources.now_nowcast_note_simple
import space.opensensor.today.resources.now_snapshot_note
import space.opensensor.today.resources.pms_absent_hint
import space.opensensor.today.resources.pms_absent_title
import space.opensensor.today.resources.pms_error_hint
import space.opensensor.today.resources.pms_error_title
import space.opensensor.today.resources.pms_mismatch_hint
import space.opensensor.today.resources.pms_mismatch_title
import space.opensensor.today.resources.pms_other_hint
import space.opensensor.today.resources.pms_other_title
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

/** Fills the first `%1$s` or `%1$d` of a template read once, for leaf lambdas that tick each second. */
internal fun fill(template: String, value: String): String =
    template.replace("%1\$s", value).replace("%1\$d", value)

@Composable
internal fun AirMood.titleText(): String = moodWord(step)

/**
 * The hero's line under the mood. Detailed reads advice that names the WHO guideline. Simple speaks
 * in moods only, so it reads the shared mood line.
 */
internal fun heroLineRes(mood: AirMood, level: DetailLevel): StringResource =
    if (level.detailed) mood.adviceRes() else moodLineRes(mood.step)

/** The hero's one line on what its number averages (#93). Simple never names the method. */
internal fun averagingLineRes(averaging: Averaging, level: DetailLevel): StringResource = when (averaging) {
    Averaging.NOWCAST -> if (level.detailed) Res.string.now_basis_nowcast else Res.string.now_basis_nowcast_simple
    Averaging.LIVE -> Res.string.now_basis_live
}

/** The first line of the hero's info sheet, what its number averages. */
internal fun averagingNoteRes(averaging: Averaging, level: DetailLevel): StringResource = when (averaging) {
    Averaging.NOWCAST -> if (level.detailed) Res.string.now_nowcast_note else Res.string.now_nowcast_note_simple
    Averaging.LIVE -> Res.string.now_snapshot_note
}

/** Whether the hero shows the mood of [reading]. Simple leaves out the mood of a flagged live value (#91). */
internal fun showsMood(reading: NowReading.Reading, level: DetailLevel): Boolean = reading.rated || level.detailed

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

/** The title of a particle sensor state, keyed by its class name, else the class title or name. */
@Composable
internal fun SensorState.titleText(): String = when {
    absent -> stringResource(Res.string.pms_absent_title)
    else -> when (cls?.name) {
        "warming" -> stringResource(Res.string.pms_warming_title)
        "stale" -> stringResource(Res.string.pms_stale_title)
        "sensor-error" -> stringResource(Res.string.pms_error_title)
        "model-mismatch" -> stringResource(Res.string.pms_mismatch_title)
        "sleeping" -> stringResource(Res.string.pms_sleeping_title)
        else -> if (valid) stringResource(Res.string.pms_valid_title) else label ?: stringResource(Res.string.pms_other_title)
    }
}

/** The hint of a particle sensor state, keyed by its class name, with one line for any other class. */
@Composable
internal fun SensorState.hintText(): String = stringResource(
    when {
        absent -> Res.string.pms_absent_hint
        else -> when (cls?.name) {
            "warming" -> Res.string.pms_warming_hint
            "stale" -> Res.string.pms_stale_hint
            "sensor-error" -> Res.string.pms_error_hint
            "model-mismatch" -> Res.string.pms_mismatch_hint
            "sleeping" -> Res.string.pms_sleeping_hint
            else -> Res.string.pms_other_hint
        }
    },
)

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

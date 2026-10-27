package space.opensensor.family.air

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.protocol.LiveSample

/** The WHO step of [value], or null for a neutral metric, a scale that is not WHO shaped or a non-finite value. */
fun MetricSpec.stepOf(value: Double): WhoStep? = healthScale?.stepOf(value)

/** The mood of [value] on this metric's WHO scale, or null when it has no step. */
fun MetricSpec.moodOf(value: Double): AirMood? = stepOf(value)?.let { AirMood.of(it) }

/** True for a template that writes the batch estimate column, the only boards whose live view may show it. */
val DeviceCapabilities.batchEstimate: Boolean get() = AirFamily.BATCH_ESTIMATE in capabilities

/** The batch estimate card, a template with the column and the board's acknowledged opt-in (`1`). */
fun DeviceCapabilities.showsBatchEstimate(boardBatchCandidate: Int?): Boolean = batchEstimate && boardBatchCandidate == 1

/**
 * The particle cards (mood, PM2.5) apply with [AirFamily.PARTICLES], once a sample carries the PM
 * sensor state. A sample without the `pms_status` key (the board has no such field) or with status
 * ABSENT does not. A `null` status is no reading now, never a missing sensor, so an overflow sample
 * keeps the particle cards with their values cleared.
 */
fun DeviceCapabilities.showsAirCards(sample: LiveSample?): Boolean {
    if (AirFamily.PARTICLES !in capabilities) return false
    val reading = reading(sample) ?: return false
    return reading.carries(AirMetrics.PMS_STATUS) && reading.pmsStatus != PmsStatus.ABSENT
}

package space.opensensor.demo

import space.opensensor.data.protocol.LiveSample
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Datastream
import space.opensensor.model.GateValue
import space.opensensor.model.ValidityGate
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The LIVE object of the demo board, built from its board template alone. Every `firmware.live_keys`
 * entry gets a value, so this names no sensor and works for any template.
 *
 * - A column that gates another (`valid_when` equals) carries the value that opens the gate.
 * - A coded column carries its first class that is not nodata.
 * - A ranged column wanders slowly around a resting level inside its plausible range.
 * - Integer columns are whole numbers, the rest have one decimal.
 *
 * The clock keys ([LiveSample.HEADER]) come from the caller.
 */
internal class DemoLive(private val template: BoardTemplate, private val rnd: Random) {

    private val streams: List<Pair<String, Datastream?>> = template.liveKeys
        .filterValues { it !in LiveSample.HEADER }
        .map { (column, key) -> key to template.datastream(column) }

    /** Column to the value that opens every gate reading it. */
    private val gateOpeners: Map<String, Double> = template.datastreams
        .mapNotNull { (it.validWhen as? ValidityGate.Equals) }
        .mapNotNull { gate ->
            when (val value = gate.value) {
                is GateValue.Number -> gate.column to value.value
                is GateValue.Flag -> gate.column to if (value.value) 1.0 else 0.0
                is GateValue.Text -> null
            }
        }
        .toMap()

    private val levels: MutableMap<String, Double> = streams
        .mapNotNull { (key, stream) -> stream?.let { key to rest(it) } }
        .toMap(mutableMapOf())

    /** Move every ranged value one small step, pulled back towards its resting level. */
    fun step() {
        for ((key, stream) in streams) {
            stream ?: continue
            val (min, max) = bounds(stream)
            val span = max - min
            val now = levels[key] ?: continue
            val pull = (rest(stream) - now) * 0.2
            levels[key] = (now + pull + rnd.nextDouble(-span, span) * 0.004).coerceIn(min, max)
        }
    }

    /** The LIVE JSON with [seq], [monoUs] and, once the clock is set, [utcNs]. */
    fun json(seq: Long, monoUs: Long, utcNs: Long?): String = buildString {
        append("{\"").append(LiveSample.SEQ).append("\":").append(seq)
        append(",\"").append(LiveSample.MONO).append("\":").append(monoUs)
        if (utcNs != null) append(",\"").append(LiveSample.UTC).append("\":").append(utcNs)
        for ((key, stream) in streams) {
            append(",\"").append(key).append("\":")
            append(if (stream == null) "null" else format(stream, value(key, stream)))
        }
        append('}')
    }

    private fun value(key: String, stream: Datastream): Double =
        gateOpeners[stream.name]
            ?: stream.classes.firstOrNull { !it.nodata }?.value?.toDouble()
            ?: levels.getValue(key)

    private fun format(stream: Datastream, value: Double): String =
        if (stream.isInteger) value.roundToLong().toString() else ((value * 10).roundToLong() / 10.0).toString()

    private fun bounds(stream: Datastream): Pair<Double, Double> =
        stream.plausibleRange?.let { it.min to it.max } ?: (0.0 to FALLBACK_SPAN)

    /** Room temperature for a Celsius column, the middle for a share or a voltage, else just above the floor. */
    private fun rest(stream: Datastream): Double {
        val (min, max) = bounds(stream)
        val level = when (stream.unit) {
            "Cel" -> ROOM_CELSIUS
            "%", "mV", "V" -> (min + max) / 2
            else -> min + (max - min) * 0.02
        }
        return level.coerceIn(min, max)
    }

    private companion object {
        const val ROOM_CELSIUS = 22.0

        /** The span of a column whose template gives no plausible range. */
        const val FALLBACK_SPAN = 100.0
    }
}

package space.opensensor.data.history

import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.SensorState
import space.opensensor.model.stateGate

/**
 * The state of the status column that gates [column] in this sample, or null when [column] has no
 * status gate or the sample has no reading of its status now.
 */
fun LiveReading.stateOf(column: String): SensorState? {
    val gate = thing.stateGate(column) ?: return null
    return SensorState.of(gate, thing.datastream(gate.column)?.datastream, value(gate.column))
}

/** True when this sample carries the status column that gates [column], even as `null`. */
fun LiveReading.carriesStateOf(column: String): Boolean = thing.stateGate(column)?.let { carries(it.column) } == true

package space.opensensor.today

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.ServedTemplates
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.model.AppProfile
import space.opensensor.model.BoardTemplate
import space.opensensor.model.Family

/**
 * A soil board no bundled profile knows, served as a template. Served alone it has no LIVE view. With
 * [profile], a reviewed generic profile whose primary is soil moisture, Today shows soil cards.
 */
internal object SoilFixture {
    const val MOISTURE = "soil_moisture"
    const val TEMPERATURE = "soil_temperature_c"
    const val DICT = "50117e5700000000000000000000000000000000000000000000000000000001"
    const val DESC = "50117e5700000000000000000000000000000000000000000000000000000002"

    val template: BoardTemplate = BoardTemplate.parse(
        """
{
  "type": "OpenSensorTemplate",
  "template_version": "0.1.0",
  "id": "soil-probe-v1",
  "instruments": ["probe"],
  "opensensor:sample_interval_s": 60,
  "item_assets": {"data": {"type": "application/vnd.apache.parquet", "roles": ["data"],
    "table:primary_datetime": "event_time_utc_us", "table:columns": [
      {"name": "event_time_utc_us", "type": "int64", "roles": ["referenceInformation"]},
      {"name": "$MOISTURE", "type": "float", "unit": "m3/m3", "roles": ["physicalMeasurement"], "opensensor:aggregate": "mean"},
      {"name": "$TEMPERATURE", "type": "float", "unit": "Cel", "roles": ["physicalMeasurement"], "opensensor:aggregate": "mean"},
      {"name": "battery_percent", "type": "int32", "unit": "%", "roles": ["auxiliaryInformation"],
        "opensensor:property": "${BoardPower.STATE_OF_CHARGE}", "opensensor:aggregate": "point"}]}},
  "links": [],
  "firmware": {"schema": "soil-probe-v1", "proto_min": 3, "dict": "$DICT",
    "live_keys": {"event_time_utc_us": "utc", "$MOISTURE": "sm", "$TEMPERATURE": "st", "battery_percent": "pct"}}
}
        """.trimIndent(),
        desc = DESC,
    )

    val info = DeviceInfo(proto = 3, schema = "soil-probe-v1", dict = DICT, desc = DESC.take(16), thing = "soil")

    /** A reviewed profile of the soil dictionary, as a bundled one would be. */
    val profile = AppProfile(dict = DICT, family = Family.GENERIC, primary = listOf(MOISTURE, TEMPERATURE))

    /** The served soil template joined with [profile]. */
    fun capabilities(): DeviceCapabilities = DeviceCapabilities.from(info, served = listOf(template)) { profile.takeIf { p -> p.dict == it } }

    /** The served soil template with no reviewed profile, as the app sees a board it has not reviewed. */
    fun unreviewed(): DeviceCapabilities = DeviceCapabilities.from(info, served = listOf(template))

    /** Runs [block] with the soil template served app wide, as `GET_DESCRIPTOR` would leave it. */
    fun <T> served(block: () -> T): T {
        ServedTemplates.shared.add(template)
        try {
            return block()
        } finally {
            ServedTemplates.shared.remove(DESC)
        }
    }
}

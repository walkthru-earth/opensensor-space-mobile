package space.opensensor.thing

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.model.DerivedMetricProvider

/**
 * An opensensor board, synced over BLE or LAN through today's collection path. It is recognized by
 * its advertised GATT service, its `_ossync._tcp` LAN service or its `INFO`, and its capabilities
 * come from [DeviceCapabilities] of that `INFO`. Its files may be published only once the owner opts
 * the board in, at H3 resolution 7 at most, the finest the public archive carries.
 */
object OpenSensorBoardKind : ThingKind {
    override val id = KindId("opensensor-board")

    override val contractVersion = 1

    /** The finest H3 resolution a published board file names (docs/devices-and-discovery.md, privacy rule 2). */
    const val PUBLISHED_MAX_RES = 7

    override val source: DataSource = DataSource.PullSync(PublishPolicy.OptInCcBy(maxRes = PUBLISHED_MAX_RES, trimSessionEnds = false))

    /** None of its own. The families the app installs name what its sensors derive ([ThingKinds.installMetrics]). */
    override val derivedMetrics: List<DerivedMetricProvider> = emptyList()

    override fun recognize(evidence: Evidence): Recognition? = when (evidence) {
        is Evidence.BleAdvert -> if (evidence.serviceUuids.any { it.equals(DeviceGatt.SERVICE, ignoreCase = true) }) Recognition(id) else null
        is Evidence.LanService -> if (evidence.type.removeSuffix(".") == LanProtocol.SERVICE_TYPE) Recognition(id) else null
        is Evidence.Info -> evidence.info.takeIf { it.proto != null }?.let { info ->
            Recognition(id, DeviceCapabilities.from(info).capabilities, info.thing)
        }
    }
}

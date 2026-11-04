package space.opensensor.thing

import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.protocol.DeviceGatt
import space.opensensor.data.protocol.LanProtocol
import space.opensensor.model.DerivedMetricProvider

/**
 * An opensensor board, synced over BLE or LAN through today's collection path. It is recognized by
 * its advertised GATT service, its `_ossync._tcp` LAN service or its `INFO`, and its capabilities
 * come from [DeviceCapabilities] of that `INFO`. Its files may be published only once the owner opts
 * the board in. A published file and its Item carry the file's own H3 cells at resolution 8, the
 * contract's resolution for every Thing, fixed or moving.
 */
object OpenSensorBoardKind : ThingKind {
    override val id = KindId("opensensor-board")

    override val contractVersion = 1

    /**
     * The H3 resolution a published board file names (docs/devices-and-discovery.md, privacy rule 2).
     * The board writes its cells at 8 by default, so the phone's Item keeps them as they are and
     * equals the board's own Item. Only a finer cell is pulled to 8, which the contract never publishes.
     */
    const val PUBLISHED_MAX_RES = 8

    override val source: DataSource = DataSource.PullSync(PublishPolicy.OptInCcBy(maxRes = PUBLISHED_MAX_RES, trimSessionEnds = false))

    /** None of its own. The families the app installs name what its sensors derive ([ThingKinds.metricProviders]). */
    override val derivedMetrics: List<DerivedMetricProvider> = emptyList()

    override fun recognize(evidence: Evidence): Recognition? = when (evidence) {
        is Evidence.BleAdvert -> if (evidence.serviceUuids.any { it.equals(DeviceGatt.SERVICE, ignoreCase = true) }) Recognition(id) else null
        is Evidence.LanService -> if (evidence.type.removeSuffix(".") == LanProtocol.SERVICE_TYPE) Recognition(id) else null
        is Evidence.Info -> evidence.info.takeIf { it.proto != null }?.let { info ->
            Recognition(id, DeviceCapabilities.from(info).capabilities, info.thing)
        }
    }
}

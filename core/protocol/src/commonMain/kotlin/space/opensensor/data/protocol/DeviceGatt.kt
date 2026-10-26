package space.opensensor.data.protocol

/** Platform-independent GATT identities. Android wraps these in UUID; iOS uses CBUUID. */
object DeviceGatt {
    const val PROTOCOL_VERSION = 3
    const val SERVICE = "255f595e-0001-47ed-8e0d-59994199b680"
    const val INFO = "255f595e-0002-47ed-8e0d-59994199b680"
    const val STATUS = "255f595e-0003-47ed-8e0d-59994199b680"
    const val LIVE = "255f595e-0004-47ed-8e0d-59994199b680"
    const val CONTROL = "255f595e-0005-47ed-8e0d-59994199b680"
    const val RESPONSE = "255f595e-0006-47ed-8e0d-59994199b680"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

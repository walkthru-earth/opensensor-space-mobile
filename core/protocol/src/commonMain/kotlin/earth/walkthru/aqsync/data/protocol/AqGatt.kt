package earth.walkthru.aqsync.data.protocol

/** Platform-independent GATT identities. Android wraps these in UUID; iOS uses CBUUID. */
object AqGatt {
    const val PROTOCOL_VERSION = 3
    const val SERVICE = "c0a5e9f0-0001-4b1a-9c3e-2d7f8a6b4e01"
    const val INFO = "c0a5e9f0-0002-4b1a-9c3e-2d7f8a6b4e01"
    const val STATUS = "c0a5e9f0-0003-4b1a-9c3e-2d7f8a6b4e01"
    const val LIVE = "c0a5e9f0-0004-4b1a-9c3e-2d7f8a6b4e01"
    const val CONTROL = "c0a5e9f0-0005-4b1a-9c3e-2d7f8a6b4e01"
    const val RESPONSE = "c0a5e9f0-0006-4b1a-9c3e-2d7f8a6b4e01"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

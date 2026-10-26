package space.opensensor.data.protocol

/**
 * Why the device closed a link (protocol 3). Carried by `BYE`, by each [DisconnectEvent] and by the
 * `dr` status key. A code this app does not know maps to [UNKNOWN], never an error.
 */
enum class DisconnectReason(val code: Int, val wireName: String) {
    UNKNOWN(0, "unknown"),
    IDLE_TIMEOUT(1, "idle-timeout"),
    HANDSHAKE_TIMEOUT(2, "handshake-timeout"),
    AUTH_FAILED(3, "auth-failed"),
    SESSIONS_FULL(4, "sessions-full"),
    SLOW_PEER(5, "slow-peer"),
    TX_OVERFLOW(6, "tx-overflow"),
    MALFORMED(7, "malformed"),
    WIFI_LOST(8, "wifi-lost"),
    LAN_DISABLED(9, "lan-disabled"),
    TOKEN_ROTATED(10, "token-rotated"),
    REBOOT(11, "reboot"),
    PEER_CLOSED(12, "peer-closed"),
    RECV_ERROR(13, "recv-error"),
    SEND_ERROR(14, "send-error"),
    SERVER_STOP(15, "server-stop"),
    KEEPALIVE_FAILED(16, "keepalive-failed");

    companion object {
        /** Reason codes 0 to 16, one `DIAG` counter each. */
        const val COUNT: Int = 17

        fun fromCode(code: Int): DisconnectReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * One 16-byte entry of the device's persistent disconnect ring. `link` is 0 for BLE, 1 to 3 for a
 * LAN slot and 255 for a refused LAN client. `err` is a signed errno or HCI code, 0 when unused.
 */
data class DisconnectEvent(
    val uptimeSeconds: Long,
    val generation: Long,
    val reason: Int,
    val link: Int,
    val err: Int,
    val queued: Int,
    val bootSeq: Int,
) {
    val disconnectReason: DisconnectReason get() = DisconnectReason.fromCode(reason)

    fun encode(): ByteArray = WireBuffer.allocate(BYTES)
        .putInt(uptimeSeconds.toInt())
        .putInt(generation.toInt())
        .put(reason.toByte())
        .put(link.toByte())
        .putShort(err.toShort())
        .putShort(queued.toShort())
        .putShort(bootSeq.toShort())
        .array()

    companion object {
        const val BYTES: Int = 16

        /** Decode the event at [offset]. Throws [FrameDecodeException] when fewer than 16 bytes remain. */
        fun decode(bytes: ByteArray, offset: Int = 0): DisconnectEvent {
            if (offset < 0 || bytes.size - offset < BYTES) throw FrameDecodeException("truncated disconnect event")
            val buf = WireBuffer.wrap(bytes.copyOfRange(offset, offset + BYTES))
            return DisconnectEvent(
                uptimeSeconds = buf.int.toLong() and 0xFFFF_FFFFL,
                generation = buf.int.toLong() and 0xFFFF_FFFFL,
                reason = buf.get().toInt() and 0xFF,
                link = buf.get().toInt() and 0xFF,
                err = buf.short.toInt(),
                queued = buf.short.toInt() and 0xFFFF,
                bootSeq = buf.short.toInt() and 0xFFFF,
            )
        }
    }
}

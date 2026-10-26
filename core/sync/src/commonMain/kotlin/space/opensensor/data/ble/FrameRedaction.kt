package space.opensensor.data.ble

import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.FrameType
import space.opensensor.data.protocol.Opcode

/**
 * The one place that decides what the Debug screen may show of a frame. The LAN token, the Wi-Fi
 * passphrase and the BLE PIN never leave this boundary in readable form (root `AGENTS.md`).
 *
 * Secret frames, per `opensensor-space-firmware/docs/shared/ble-sync-protocol.md`:
 * - RX `TOKEN` (0x46) carries the 32-byte LAN bearer token.
 * - TX `SET_CONFIG` (0x09) can carry `wifi.psk` and `ble.pin`.
 * - TX bytes that start with the LAN handshake magic `OSS1` carry the token. The handshake is
 *   unframed and normally never reaches a recorder; this rule is a second fence.
 *
 * A secret frame keeps only its type byte. `GET_CONFIG`/`CONFIG` report `psk_set` and `pin_set`
 * instead of the values, and [scrubSecrets] still masks the exact keys `psk`, `pin` and `token`
 * should a firmware ever echo them.
 */
object FrameRedaction {
    const val REDACTED = "<redacted>"
    private const val ELLIPSIS = "…"
    private const val HEX = "0123456789abcdef"
    private val LAN_HELLO_MAGIC = byteArrayOf(0x4F, 0x53, 0x53, 0x31) // "OSS1"
    private val JSON_STRING_OR_NUMBER_MEMBER = Regex("(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9][0-9.eE+-]*)")
    private val SECRET_KEY_PARTS = listOf("psk", "pin", "token", "pass", "secret")

    fun isSecret(direction: FrameDirection, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val type = bytes[0].toInt() and 0xFF
        return when (direction) {
            FrameDirection.RX -> type == FrameType.TOKEN
            FrameDirection.TX -> type == Opcode.SET_CONFIG || bytes.startsWith(LAN_HELLO_MAGIC)
        }
    }

    /**
     * Hex preview of at most [maxBytes] bytes. A secret frame shows its type byte and an ellipsis.
     * A cut preview is not marked here, the screen compares it with the frame length.
     */
    fun preview(direction: FrameDirection, bytes: ByteArray, maxBytes: Int = RawFrameRecord.PREVIEW_BYTES): String {
        if (bytes.isEmpty()) return ""
        if (isSecret(direction, bytes)) return hex(bytes, 1) + ELLIPSIS
        return hex(bytes, minOf(bytes.size, maxBytes))
    }

    /** Full hex of a reply for the raw command result. A secret reply keeps its type byte only. */
    fun replyHex(raw: ByteArray): String =
        if (isSecret(FrameDirection.RX, raw)) hex(raw, 1) + " " + REDACTED else hex(raw, raw.size)

    /** One readable line per decoded frame. Never prints a token, and scrubs secret JSON keys. */
    fun describe(frame: Frame): String = when (frame) {
        is Frame.Token -> "TOKEN port=${frame.port} token=$REDACTED"
        is Frame.Chunk -> "CHUNK handle=${frame.handle} offset=${frame.offset} len=${frame.payload.size}"
        is Frame.Log -> "LOG ${frame.bytes.size} bytes"
        is Frame.Config -> "CONFIG flags=${frame.flags} ${scrubSecrets(frame.json)}"
        is Frame.ConfigChunk -> "CONFIG_CHUNK offset=${frame.offset} total=${frame.total} len=${frame.bytes.size}"
        is Frame.StatusPush -> "STATUS ${scrubSecrets(frame.json)}"
        is Frame.LivePush -> "LIVE ${scrubSecrets(frame.json)}"
        is Frame.Hello -> "HELLO proto=${frame.proto} payload_max=${frame.payloadMax} ${scrubSecrets(frame.infoJson)}"
        is Frame.Bye -> "BYE ${frame.disconnectReason.wireName} gen=${frame.generation} err=${frame.err} queued=${frame.queued} up=${frame.uptimeSeconds}s"
        is Frame.Diag -> "DIAG boot=${frame.bootSeq} events=${frame.events.size} counts=${frame.counts.joinToString(",")}"
        is Frame.Aborted -> "ABORTED op=${Opcode.name(frame.op)}"
        is Frame.DescriptorChunk -> "DESCRIPTOR_CHUNK offset=${frame.offset} total=${frame.total} len=${frame.bytes.size}"
        else -> frame.toString()
    }

    /**
     * Replace the string or number value of any key whose name contains `psk`, `pin`, `token`,
     * `pass` (and so `password`) or `secret`, in any case and at any depth, dotted names such as
     * `wifi.psk` included. Keys ending in `_set` (`psk_set`, `pin_set`) only say whether a secret
     * exists and stay as they are. Masking a harmless key by mistake is the safe side.
     */
    fun scrubSecrets(json: String): String = JSON_STRING_OR_NUMBER_MEMBER.replace(json) { m ->
        if (isSecretKey(m.groupValues[2])) m.groupValues[1] + "\"" + REDACTED + "\"" else m.value
    }

    /** True for a JSON key [scrubSecrets] masks. */
    fun isSecretKey(key: String): Boolean {
        val k = key.lowercase()
        if (k.endsWith("_set")) return false
        return SECRET_KEY_PARTS.any { it in k }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun hex(bytes: ByteArray, count: Int): String = CharArray(count * 2) { index ->
        val byte = bytes[index / 2].toInt() and 0xFF
        HEX[if (index % 2 == 0) byte ushr 4 else byte and 0x0F]
    }.concatToString()
}

package earth.walkthru.aqsync.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary `control` requests and `response` frames from docs/ble-sync-protocol.md.
 *
 * Every multi-byte integer is little-endian. Text is UTF-8 with no terminator; its length is
 * implied by the frame length. The first byte of a request is the opcode; the first byte of a
 * response frame is the frame type.
 */
object Opcode {
    const val LIST: Int = 0x01
    const val OPEN: Int = 0x02
    const val READ: Int = 0x03
    const val CLOSE: Int = 0x04
    const val SET_TIME: Int = 0x05
    const val FLUSH: Int = 0x06
    const val STATUS: Int = 0x07

    fun name(op: Int): String = when (op) {
        LIST -> "LIST"
        OPEN -> "OPEN"
        READ -> "READ"
        CLOSE -> "CLOSE"
        SET_TIME -> "SET_TIME"
        FLUSH -> "FLUSH"
        STATUS -> "STATUS"
        else -> "0x%02x".format(op)
    }
}

object FrameType {
    const val FILE: Int = 0x10
    const val LIST_END: Int = 0x11
    const val OPENED: Int = 0x20
    const val CHUNK: Int = 0x21
    const val READ_END: Int = 0x22
    const val CLOSED: Int = 0x23
    const val TIME_SET: Int = 0x30
    const val FLUSHED: Int = 0x31
    const val ERROR: Int = 0x7F
}

/** Error codes carried by `ERROR` frames and by `READ_END.status`. */
enum class ProtocolError(val code: Int, val label: String) {
    MALFORMED_REQUEST(1, "malformed request"),
    INVALID_NAME(2, "invalid name"),
    NOT_FINALIZED_PARQUET(3, "not a finalized Parquet file"),
    OPEN_FAILED(4, "open failed"),
    BAD_HANDLE(5, "bad handle"),
    RANGE_OUTSIDE_FILE(6, "range outside file"),
    BUSY(7, "busy (command queue full or transfer in progress)"),
    STORAGE_UNAVAILABLE(8, "storage unavailable"),
    INVALID_EPOCH(9, "invalid epoch (outside 2020–2100)"),
    NOTHING_TO_FLUSH(10, "nothing to flush"),
    UNKNOWN_OPCODE(11, "unknown opcode");

    companion object {
        fun fromCode(code: Int): ProtocolError? = entries.firstOrNull { it.code == code }
        fun describe(code: Int): String = fromCode(code)?.label ?: "unknown error code $code"
    }
}

/** A `control` request, ready to be written to the characteristic. */
sealed interface Request {
    val opcode: Int
    fun encode(): ByteArray

    data object List : Request {
        override val opcode: Int = Opcode.LIST
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    data class Open(val name: String) : Request {
        override val opcode: Int = Opcode.OPEN
        override fun encode(): ByteArray {
            val utf8 = name.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(1 + utf8.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(opcode.toByte()).put(utf8).array()
        }
    }

    /** `handle` u16, `offset` u32, `length` u32. */
    data class Read(val handle: Int, val offset: Long, val length: Long) : Request {
        override val opcode: Int = Opcode.READ
        override fun encode(): ByteArray = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            .put(opcode.toByte())
            .putShort(handle.toShort())
            .putInt(offset.toInt())
            .putInt(length.toInt())
            .array()
    }

    data class Close(val handle: Int) : Request {
        override val opcode: Int = Opcode.CLOSE
        override fun encode(): ByteArray = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
            .put(opcode.toByte()).putShort(handle.toShort()).array()
    }

    /** `epoch_s` i64. */
    data class SetTime(val epochSeconds: Long) : Request {
        override val opcode: Int = Opcode.SET_TIME
        override fun encode(): ByteArray = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(opcode.toByte()).putLong(epochSeconds).array()
    }

    data object Flush : Request {
        override val opcode: Int = Opcode.FLUSH
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }

    data object Status : Request {
        override val opcode: Int = Opcode.STATUS
        override fun encode(): ByteArray = byteArrayOf(opcode.toByte())
    }
}

/** A decoded `response` notification. */
sealed interface Frame {
    /** `bytes` u32, `name` utf8. */
    data class File(val bytes: Long, val name: String) : Frame

    /** `count` u16, `partials` u16, `sd_kib` u32, `sd_used_kib` u32. */
    data class ListEnd(val count: Int, val partials: Int, val sdKib: Long, val sdUsedKib: Long) : Frame

    /** `handle` u16, `size` u32, `crc32` u32, `name` utf8. */
    data class Opened(val handle: Int, val size: Long, val crc32: Long, val name: String) : Frame

    /** `handle` u16, `offset` u32, `payload`. */
    class Chunk(val handle: Int, val offset: Long, val payload: ByteArray) : Frame {
        override fun toString(): String = "Chunk(handle=$handle, offset=$offset, len=${payload.size})"
    }

    /** `handle` u16, `next_offset` u32, `status` u8 (0 = ok, else error code). */
    data class ReadEnd(val handle: Int, val nextOffset: Long, val status: Int) : Frame {
        val ok: Boolean get() = status == 0
    }

    data class Closed(val handle: Int) : Frame

    /** `epoch_s` i64, `monotonic_us` i64. */
    data class TimeSet(val epochSeconds: Long, val monotonicMicros: Long) : Frame

    /** `rows` u16 written, `fin` u32 files finalized so far. */
    data class Flushed(val rows: Int, val filesFinalized: Long) : Frame

    /** `op` u8, `code` u8, `detail` utf8. */
    data class Error(val op: Int, val code: Int, val detail: String) : Frame {
        val error: ProtocolError? get() = ProtocolError.fromCode(code)
        fun describe(): String = buildString {
            append(Opcode.name(op)).append(" failed: ").append(ProtocolError.describe(code))
            if (detail.isNotEmpty()) append(" (").append(detail).append(')')
        }
    }
}

class FrameDecodeException(message: String) : Exception(message)

object FrameCodec {
    /** Decode one `response` notification. Throws [FrameDecodeException] on truncated/unknown frames. */
    fun decode(raw: ByteArray): Frame {
        if (raw.isEmpty()) throw FrameDecodeException("empty frame")
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.get().toInt() and 0xFF
        return try {
            when (type) {
                FrameType.FILE -> Frame.File(bytes = buf.u32(), name = buf.restUtf8())
                FrameType.LIST_END -> Frame.ListEnd(
                    count = buf.u16(), partials = buf.u16(), sdKib = buf.u32(), sdUsedKib = buf.u32()
                )
                FrameType.OPENED -> Frame.Opened(
                    handle = buf.u16(), size = buf.u32(), crc32 = buf.u32(), name = buf.restUtf8()
                )
                FrameType.CHUNK -> {
                    val handle = buf.u16()
                    val offset = buf.u32()
                    val payload = ByteArray(buf.remaining()).also { buf.get(it) }
                    Frame.Chunk(handle, offset, payload)
                }
                FrameType.READ_END -> Frame.ReadEnd(handle = buf.u16(), nextOffset = buf.u32(), status = buf.u8())
                FrameType.CLOSED -> Frame.Closed(handle = buf.u16())
                FrameType.TIME_SET -> Frame.TimeSet(epochSeconds = buf.long, monotonicMicros = buf.long)
                FrameType.FLUSHED -> Frame.Flushed(rows = buf.u16(), filesFinalized = buf.u32())
                FrameType.ERROR -> Frame.Error(op = buf.u8(), code = buf.u8(), detail = buf.restUtf8())
                else -> throw FrameDecodeException("unknown frame type 0x%02x".format(type))
            }
        } catch (e: java.nio.BufferUnderflowException) {
            throw FrameDecodeException("truncated frame type 0x%02x (%d bytes)".format(type, raw.size))
        }
    }

    /**
     * Encode a response frame. The phone never sends these; the encoder exists for the in-app
     * fake device and for round-trip tests of the decoder.
     */
    fun encode(frame: Frame): ByteArray {
        fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        return when (frame) {
            is Frame.File -> {
                val n = frame.name.toByteArray(Charsets.UTF_8)
                le(5 + n.size).put(FrameType.FILE.toByte()).putInt(frame.bytes.toInt()).put(n).array()
            }
            is Frame.ListEnd -> le(13).put(FrameType.LIST_END.toByte())
                .putShort(frame.count.toShort()).putShort(frame.partials.toShort())
                .putInt(frame.sdKib.toInt()).putInt(frame.sdUsedKib.toInt()).array()
            is Frame.Opened -> {
                val n = frame.name.toByteArray(Charsets.UTF_8)
                le(11 + n.size).put(FrameType.OPENED.toByte()).putShort(frame.handle.toShort())
                    .putInt(frame.size.toInt()).putInt(frame.crc32.toInt()).put(n).array()
            }
            is Frame.Chunk -> le(7 + frame.payload.size).put(FrameType.CHUNK.toByte())
                .putShort(frame.handle.toShort()).putInt(frame.offset.toInt()).put(frame.payload).array()
            is Frame.ReadEnd -> le(8).put(FrameType.READ_END.toByte()).putShort(frame.handle.toShort())
                .putInt(frame.nextOffset.toInt()).put(frame.status.toByte()).array()
            is Frame.Closed -> le(3).put(FrameType.CLOSED.toByte()).putShort(frame.handle.toShort()).array()
            is Frame.TimeSet -> le(17).put(FrameType.TIME_SET.toByte())
                .putLong(frame.epochSeconds).putLong(frame.monotonicMicros).array()
            is Frame.Flushed -> le(7).put(FrameType.FLUSHED.toByte())
                .putShort(frame.rows.toShort()).putInt(frame.filesFinalized.toInt()).array()
            is Frame.Error -> {
                val d = frame.detail.toByteArray(Charsets.UTF_8)
                le(3 + d.size).put(FrameType.ERROR.toByte()).put(frame.op.toByte()).put(frame.code.toByte()).put(d).array()
            }
        }
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFF_FFFFL
    private fun ByteBuffer.restUtf8(): String {
        val bytes = ByteArray(remaining()).also { get(it) }
        return String(bytes, Charsets.UTF_8)
    }
}

/** Hex helpers for logs and test fixtures. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

package earth.walkthru.aqsync.data.protocol

/**
 * Advertising service data, protocol revision 2.1 (firmware `-v6.3`): ten little-endian bytes the
 * device broadcasts under [AqGatt.SERVICE] so a phone learns "something changed" without
 * connecting — and so an offloaded scan filter can wake the app on a flag bit. Layout and rules:
 * `../aq-parquet/docs/shared/ble-sync-protocol.md`, "Advertising payload (v2.1)".
 *
 * Everything in it is public to anyone in range; it carries presence, counters and health bits only.
 */
data class Advertisement(
    val version: Int,
    val flags: Int,
    /** `status.fin`: files finalized this boot. */
    val finalized: Long,
    /** Last four hex digits of `info.boot`, lower-case, so a reboot is visible as a change. */
    val boot16: String,
) {
    /** No UTC anchor this boot: rows are landing in `unsynced`, a phone in range should send `SET_TIME`. */
    val noUtc: Boolean get() = flags and FLAG_NO_UTC != 0

    /** A file finalized since the last `LIST` answered on any link. */
    val newFiles: Boolean get() = flags and FLAG_NEW_FILES != 0
    val sdMounted: Boolean get() = flags and FLAG_SD != 0

    /** Wi-Fi connected and the mDNS/TCP server up: an mDNS browse is worth its 10 s. */
    val lanUp: Boolean get() = flags and FLAG_LAN != 0
    val storageFailed: Boolean get() = flags and FLAG_FAIL != 0

    /** `status.clk == 2`: dated from the RTC only; a time refresh is welcome, not urgent. */
    val clockRestored: Boolean get() = flags and FLAG_CLOCK_RESTORED != 0

    /** Anything a phone should act on: new files to pull, or a clock to set. */
    val wantsSync: Boolean get() = newFiles || noUtc

    /** Stable key of "what the device had to say"; equal keys need no second run. */
    val changeKey: String get() = "$boot16:$finalized:${flags and (FLAG_NO_UTC or FLAG_NEW_FILES)}"

    companion object {
        const val VERSION = 1
        const val LENGTH = 10
        const val FLAG_NO_UTC = 1 shl 0
        const val FLAG_NEW_FILES = 1 shl 1
        const val FLAG_SD = 1 shl 2
        const val FLAG_LAN = 1 shl 3
        const val FLAG_FAIL = 1 shl 4
        const val FLAG_CLOCK_RESTORED = 1 shl 5

        /** Offset of the flags byte, for `ScanFilter.setServiceData(uuid, data, mask)`. */
        const val FLAGS_OFFSET = 1

        /**
         * Decode the service-data bytes for [AqGatt.SERVICE]. Null for a pre-2.1 device (no service
         * data), an unknown version or a short payload; the two reserved bytes are ignored.
         */
        fun decode(bytes: ByteArray?): Advertisement? {
            if (bytes == null || bytes.size < 8 || bytes[0].toInt() != VERSION) return null
            val fin = (bytes[2].toLong() and 0xff) or
                ((bytes[3].toLong() and 0xff) shl 8) or
                ((bytes[4].toLong() and 0xff) shl 16) or
                ((bytes[5].toLong() and 0xff) shl 24)
            val boot = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
            return Advertisement(
                version = bytes[0].toInt(),
                flags = bytes[1].toInt() and 0xff,
                finalized = fin,
                boot16 = boot.toString(16).padStart(4, '0'),
            )
        }

        /**
         * `data`/`mask` pair for a hardware scan filter that matches when [flag] is set in the flags
         * byte: both arrays cover bytes 0–1, byte 0 (version) masked out.
         */
        fun flagFilter(flag: Int): Pair<ByteArray, ByteArray> =
            byteArrayOf(0, flag.toByte()) to byteArrayOf(0, flag.toByte())
    }
}

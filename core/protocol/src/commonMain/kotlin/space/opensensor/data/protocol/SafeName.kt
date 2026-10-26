package space.opensensor.data.protocol

/**
 * The archive file-name rule from the firmware's `docs/shared/ble-sync-protocol.md` (Rules, "Names"),
 * mirroring `safe_parquet_name()` in `firmware/common/src/sync_codec.h`.
 *
 * A name is a relative Hive path such as `station=…/year=…/month=…/day=…/data_….parquet`. It is valid
 * when it uses only `[0-9a-z._=/-]`, contains no `..` and no `//`, does not start with `/`, ends in
 * `.parquet` with at least one character before it, and is at most [MAX_BYTES] bytes long. The
 * charset is ASCII, so the byte length equals the character count for every accepted name.
 *
 * The phone adds one rule the firmware does not have. No segment between slashes may exceed
 * [MAX_SEGMENT_BYTES], so every sibling of a name still fits the 255-byte file-name limit of APFS
 * and ext4/f2fs (#60). That covers `<segment>.partial.meta` and the `<segment>.verified.json` receipts
 * older iOS builds wrote, which the iOS import still reads.
 */
object SafeName {
    /** Longest name the firmware lists and opens (`OPENED.name` fits one MTU-517 PDU). */
    const val MAX_BYTES: Int = 399

    /** 255 minus `.verified.json` (14 bytes), the longest suffix beside a name segment. */
    const val MAX_SEGMENT_BYTES: Int = 255 - 14

    private const val SUFFIX = ".parquet"

    fun isValid(name: String): Boolean {
        // Every allowed character is one UTF-8 byte, so a name that passes the charset check below
        // has exactly `length` bytes. Checking the length first bounds the work on hostile input.
        if (name.length <= SUFFIX.length || name.length > MAX_BYTES) return false
        if (name[0] == '/' || !name.endsWith(SUFFIX)) return false
        var segment = 0
        for (c in name) {
            if (!allowed(c)) return false
            segment = if (c == '/') 0 else segment + 1
            if (segment > MAX_SEGMENT_BYTES) return false
        }
        return !name.contains("..") && !name.contains("//")
    }

    private fun allowed(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c == '.' || c == '_' || c == '=' || c == '/' || c == '-'
}

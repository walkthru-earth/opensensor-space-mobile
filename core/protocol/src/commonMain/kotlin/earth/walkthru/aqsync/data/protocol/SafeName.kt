package earth.walkthru.aqsync.data.protocol

/**
 * The archive file-name rule from the firmware's `docs/shared/ble-sync-protocol.md` (Rules, "Names"),
 * mirroring `safe_parquet_name()` in `firmware/common/src/sync_codec.h`.
 *
 * A name is a relative Hive path such as `station=…/year=…/month=…/day=…/data_….parquet`. It is valid
 * when it uses only `[0-9a-z._=/-]`, contains no `..` and no `//`, does not start with `/`, ends in
 * `.parquet` with at least one character before it, and is at most [MAX_BYTES] bytes long. The
 * charset is ASCII, so the byte length equals the character count for every accepted name.
 */
object SafeName {
    /** Longest name the firmware lists and opens (`OPENED.name` fits one MTU-517 PDU). */
    const val MAX_BYTES: Int = 399

    private const val SUFFIX = ".parquet"

    fun isValid(name: String): Boolean {
        // Every allowed character is one UTF-8 byte, so a name that passes the charset check below
        // has exactly `length` bytes. Checking the length first bounds the work on hostile input.
        if (name.length <= SUFFIX.length || name.length > MAX_BYTES) return false
        if (name[0] == '/' || !name.endsWith(SUFFIX)) return false
        for (c in name) if (!allowed(c)) return false
        return !name.contains("..") && !name.contains("//")
    }

    private fun allowed(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c == '.' || c == '_' || c == '=' || c == '/' || c == '-'
}

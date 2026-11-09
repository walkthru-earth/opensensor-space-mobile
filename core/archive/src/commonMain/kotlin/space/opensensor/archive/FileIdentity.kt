package space.opensensor.archive

import space.opensensor.model.FileChecksum

/**
 * What makes a device file the same file wherever it sits, in the phone archive, on the card or in
 * the cloud (#273). The Thing that wrote it, its File Item id and the SHA-256 of its bytes, the
 * `file:checksum` of its Item. Two copies with the same identity hold the same rows, so a reader
 * keeps one and never counts the other.
 *
 * The File Item id is the file name without its folders and without `.parquet`, as `FileItems.build`
 * sets it. A board names each file by its window, boot id and sequence range, so within one Thing the
 * id names one file, and its Hive folders follow from the same window. That holds for board files
 * only ([isBoardFile]). Any other file, such as a compacted day file, can share its bare name with
 * files of other days, so only its full Hive name tells it apart.
 */
data class FileIdentity(val thing: String, val itemId: String, val sha256: String) {
    init {
        require(thing.isNotEmpty()) { "empty thing id" }
        require(itemId.isNotEmpty() && '/' !in itemId) { "not a File Item id" }
        require(FileChecksum.isSha256Hex(sha256)) { "not a SHA-256 hex digest" }
    }

    /** The `file:checksum` multihash of these bytes. */
    val checksum: String get() = FileChecksum.multihash(sha256)

    companion object {
        /** A dated board file, `data_HHMM_<boot>_<first>-<last>-<attempt>.parquet`, named by its window. */
        private val BOARD_FILE = Regex("^data_[0-9]{4}_[^/]+\\.parquet$")

        /** Whether [name], a Hive path or a bare file name, is a board file, whose File Item id names one file. */
        fun isBoardFile(name: String): Boolean = BOARD_FILE.matches(name.substringAfterLast('/'))

        /** The File Item id of a file at [name], a Hive path or a bare file name. */
        fun itemIdOf(name: String): String = name.substringAfterLast('/').removeSuffix(".parquet")

        /** The identity of [name] in [thing], or null when [sha256] is no SHA-256 (an older receipt holds none). */
        fun of(thing: String, name: String, sha256: String): FileIdentity? {
            val id = itemIdOf(name)
            if (thing.isEmpty() || id.isEmpty() || !FileChecksum.isSha256Hex(sha256)) return null
            return FileIdentity(thing, id, sha256)
        }
    }
}

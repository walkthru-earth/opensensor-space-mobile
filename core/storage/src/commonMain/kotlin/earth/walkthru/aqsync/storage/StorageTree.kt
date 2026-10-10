package earth.walkthru.aqsync.storage

/** One file or directory below a listed directory. */
data class StorageEntry(
    /** Path relative to the listed directory, `/` separated. */
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val modifiedEpochMs: Long,
)

/**
 * The plain directory walk and delete the startup janitor sweeps with. Links are never followed.
 * Adapters do the system calls only. What may be deleted is the janitor's decision.
 */
interface StorageTree {
    /** Every entry below [dir], recursively. Empty when [dir] does not exist. */
    fun list(dir: String): List<StorageEntry>

    /** Removes the file or directory tree at [path]. No error when it is already gone. */
    fun delete(path: String)
}

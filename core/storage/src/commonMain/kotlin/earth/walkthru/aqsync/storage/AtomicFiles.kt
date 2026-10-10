package earth.walkthru.aqsync.storage

/**
 * The one write protocol of every file the phone stores (#60). Paths are absolute. Every method
 * blocks on disk I/O, so callers run it on their I/O dispatcher.
 *
 * A file of class [StorageClass.Durable] is flushed through the drive cache before it is renamed
 * into place (`F_FULLFSYNC` on iOS, `fsync` elsewhere), and the parent directory is synced after,
 * so a power cut leaves either the old state or the new one. Other classes get `fsync` and the same
 * directory sync. Temp files live in the [StorageRole.Temp] directory with names starting with
 * [TEMP_PREFIX], where the startup janitor finds the ones a dead process left behind.
 */
interface AtomicFiles {
    /** Replaces [target] with [bytes] through a synced temp file and a rename, then syncs the parent. */
    fun writeAtomically(target: String, bytes: ByteArray, cls: StorageClass)

    /**
     * Moves [from] to [to] without ever replacing [to]. [from] is synced first, as [cls] asks, and the
     * parent of [to] after. Throws [AlreadyCommitted] when [to] exists. [from] is then left in place.
     */
    fun commitNoReplace(from: String, to: String, cls: StorageClass)

    /**
     * Writes [bytes] at [target] only when nothing is there, through a synced temp file and
     * [commitNoReplace]. Throws [AlreadyCommitted] when [target] exists. For immutable receipts.
     */
    fun createNoReplace(target: String, bytes: ByteArray, cls: StorageClass)

    /** Moves [from] over [to], replacing it, with the same syncs as [commitNoReplace]. Rebuildable outputs only. */
    fun commitReplace(from: String, to: String, cls: StorageClass)

    /** A new empty temp file on the volume of every root, for a writer that streams its output. */
    fun createTemp(suffix: String = ".tmp"): String

    /** Flushes [path] to storage as [cls] asks. */
    fun syncFile(path: String, cls: StorageClass)

    /** Syncs the entries of [dir], so a rename or create inside it survives a power cut. */
    fun syncDir(dir: String)

    companion object {
        /** Every temp file name starts with this. */
        const val TEMP_PREFIX: String = ".aqtmp-"

        /** The [StorageRole.Temp] directory's name inside the app's base directory (`filesDir`, Application Support). */
        const val TEMP_DIR: String = "storage-tmp"
    }
}

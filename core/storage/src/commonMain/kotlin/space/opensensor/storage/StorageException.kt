package space.opensensor.storage

/**
 * A storage write that did not reach the disk as asked. Messages name the step, never the path:
 * archive paths carry station ids (AGENTS.md).
 */
open class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A no-replace commit found its destination already there. The existing file is untouched and the
 * source is left in place for the caller to discard. In the sync engine this is the waiter's path:
 * another writer verified and committed the same file first.
 */
class AlreadyCommitted : StorageException("destination already exists, nothing was replaced")

/**
 * True when [error] or one of its causes is the platform's out of space failure (POSIX `ENOSPC`,
 * "No space left on device"). Platform file APIs report it as a plain I/O error, so callers map it
 * to [StorageException] by message.
 */
fun isOutOfSpace(error: Throwable): Boolean {
    var t: Throwable? = error
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is StorageException) return true
        val m = t.message.orEmpty()
        if ("ENOSPC" in m || m.contains("No space left", ignoreCase = true)) return true
        t = t.cause
    }
    return false
}

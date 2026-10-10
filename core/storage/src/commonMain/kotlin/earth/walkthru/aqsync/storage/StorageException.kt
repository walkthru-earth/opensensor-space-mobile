package earth.walkthru.aqsync.storage

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

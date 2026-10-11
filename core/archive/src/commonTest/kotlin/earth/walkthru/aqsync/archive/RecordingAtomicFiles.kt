package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.StorageClass

/**
 * Real platform [AtomicFiles] that records which directories each commit leaves to the protocol.
 *
 * Before a commit it walks up from the target's parent to the first directory that exists. The ones
 * missing go to [created], and those plus the first existing one go to [synced], the levels the
 * protocol syncs (`AtomicFilesContract.aCommitSyncsEveryDirectoryItCreatesUpToTheFirstThatExisted`).
 * A store that makes a directory itself with a plain mkdirs leaves it out of both lists. The real
 * syncs are not observable from here because their seams are internal to `:core:storage`.
 */
class RecordingAtomicFiles(
    private val delegate: AtomicFiles,
    private val isDirectory: (String) -> Boolean,
) : AtomicFiles {
    val created = mutableListOf<String>()
    val synced = mutableListOf<String>()

    private fun recordCommit(target: String) {
        var dir = target.substringBeforeLast('/')
        while (!isDirectory(dir) && '/' in dir) {
            created += dir
            synced += dir
            dir = dir.substringBeforeLast('/')
        }
        synced += dir
    }

    private inline fun commit(target: String, block: () -> Unit) {
        val before = created.size
        recordCommit(target)
        block()
        for (dir in created.subList(before, created.size)) check(isDirectory(dir)) { "$dir was not created by the commit" }
    }

    override fun writeAtomically(target: String, bytes: ByteArray, cls: StorageClass) =
        commit(target) { delegate.writeAtomically(target, bytes, cls) }

    override fun commitNoReplace(from: String, to: String, cls: StorageClass) = commit(to) { delegate.commitNoReplace(from, to, cls) }

    override fun createNoReplace(target: String, bytes: ByteArray, cls: StorageClass) =
        commit(target) { delegate.createNoReplace(target, bytes, cls) }

    override fun commitReplace(from: String, to: String, cls: StorageClass) = commit(to) { delegate.commitReplace(from, to, cls) }

    override fun createTemp(suffix: String): String = delegate.createTemp(suffix)

    override fun syncFile(path: String, cls: StorageClass) = delegate.syncFile(path, cls)

    override fun syncDir(dir: String) {
        synced += dir
        delegate.syncDir(dir)
    }
}

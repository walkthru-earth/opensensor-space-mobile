package space.opensensor.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory

/** [StorageTree] over `java.nio`. Links are listed as files and never followed or deleted through. */
class JvmStorageTree : StorageTree {
    override fun list(dir: String): List<StorageEntry> {
        val root = File(dir).toPath()
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val out = ArrayList<StorageEntry>()
        try {
            walk(root, out)
        } catch (e: java.io.UncheckedIOException) {
            throw StorageException("cannot list directory (${e.cause?.let { it::class.simpleName }})", e)
        }
        return out
    }

    private fun walk(root: Path, out: MutableList<StorageEntry>) {
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (path == root) return@forEach
                val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return@forEach
                out += StorageEntry(
                    path = root.relativize(path).joinToString("/"),
                    isDirectory = attrs.isDirectory,
                    bytes = if (attrs.isRegularFile) attrs.size() else 0L,
                    modifiedEpochMs = attrs.lastModifiedTime().toMillis(),
                )
            }
        }
    }

    override fun delete(path: String) {
        val target = File(path).toPath()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (target.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            Files.list(target).use { children -> children.toList() }.forEach { delete(it.toString()) }
        }
        try {
            Files.deleteIfExists(target)
        } catch (e: java.io.IOException) {
            throw StorageException("cannot delete (${e::class.simpleName})", e)
        }
    }

    private fun Path.joinToString(separator: String): String = (0 until nameCount).joinToString(separator) { getName(it).toString() }
}

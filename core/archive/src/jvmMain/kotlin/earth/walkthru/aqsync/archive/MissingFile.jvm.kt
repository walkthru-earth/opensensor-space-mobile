package earth.walkthru.aqsync.archive

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/**
 * A final is gone when the store said so, when `java.nio` found no file, or when `LocalFileReader`
 * found no regular file at the path before it opened it. A `FileNotFoundException` with another
 * reason, such as `(Permission denied)` or `(Too many open files)`, is not.
 */
internal actual fun isMissingFile(error: Throwable): Boolean = when (error) {
    is ArchiveFileMissingException, is NoSuchFileException -> true
    is FileNotFoundException -> error.message.orEmpty().let { it.endsWith("is not a regular file") || it.endsWith("(No such file or directory)") }
    else -> false
}

package space.opensensor.archive

import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.data.history.parquet.LocalFileReader
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException

/**
 * A final is gone only when the store said so with [ArchiveFileMissingException], or when `java.nio`
 * found no file. Any other open error, such as a denied permission, is not.
 */
internal actual fun isMissingFile(error: Throwable): Boolean = error is ArchiveFileMissingException || error is NoSuchFileException

/**
 * Opens an archive file. It throws [ArchiveFileMissingException] only when nothing is at [file]. A
 * path that cannot be checked, such as one under a folder that cannot be searched, rethrows the
 * reader's own error, so an export fails on it (#168).
 */
fun openArchiveFile(file: File): ByteReader = try {
    LocalFileReader(file)
} catch (e: FileNotFoundException) {
    if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ArchiveFileMissingException("${file.name} is gone").apply { initCause(e) }
    throw e
}

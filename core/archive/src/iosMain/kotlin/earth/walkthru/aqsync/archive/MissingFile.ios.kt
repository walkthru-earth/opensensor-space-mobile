package earth.walkthru.aqsync.archive

/** [IosArchiveStore.reader] checks a final with `lstat` and throws [ArchiveFileMissingException] only on ENOENT. */
internal actual fun isMissingFile(error: Throwable): Boolean = error is ArchiveFileMissingException

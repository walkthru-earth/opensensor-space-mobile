package space.opensensor.archive

import space.opensensor.data.history.parquet.LocalFileReader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * [ExportSpace] in a JVM directory. Android passes `cacheDir/export`, the folder its `FileProvider`
 * serves, so each run is `export/run-<uuid>/` and a staged file is shareable as a content URI. Every
 * space over the same root shares the leases of [ExportRuns], so two thing controllers never sweep
 * each other's staging. [clock] is wall time, the clock of `File.lastModified`.
 */
class FileExportSpace(private val root: File, private val clock: () -> Long = System::currentTimeMillis) : ExportSpace {
    override fun newRun(): ExportRun {
        sweepExpired()
        val name = ExportRuns.PREFIX + UUID.randomUUID()
        // Leased before the directory exists, so no sweep ever sees it unleased while it stages.
        ExportRuns.lease(name)
        val dir = File(root, name)
        try {
            check(dir.mkdirs()) { "cannot create the export folder" }
        } catch (t: Throwable) {
            ExportRuns.release(name)
            throw t
        }
        return FileExportRun(dir, name, clock)
    }

    override fun freeBytes(): Long? = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace

    /** Removes the released runs past [ExportRuns.MAX_AGE_MS]. Only `run-` directories, never the root. */
    private fun sweepExpired() {
        val now = clock()
        for (child in root.listFiles().orEmpty()) {
            // A real directory only, never through a link, so a `run-` link cannot empty another folder.
            if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) && ExportRuns.isExpired(child.name, now) { child.lastModified().takeIf { it > 0L } }) {
                child.deleteRecursively()
            }
        }
    }
}

private class FileExportRun(private val dir: File, private val name: String, private val clock: () -> Long) : ExportRun {
    override fun path(name: String): String = file(name).absolutePath

    override fun create(name: String): ExportSink {
        val out = FileOutputStream(file(name)).buffered(64 * 1024)
        return object : ExportSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = out.close()
        }
    }

    override fun release() {
        // The age counts from the hand over. The time is set before the lease ends (ExportRuns.isExpired).
        if (ExportRuns.isLeased(name)) dir.setLastModified(clock())
        ExportRuns.release(name)
    }

    override fun delete() {
        dir.deleteRecursively()
        ExportRuns.release(name)
    }

    private fun file(name: String): File {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "unsafe export name" }
        return File(dir, name)
    }
}

/**
 * Every regular file under [root] as an [ExportEntry] named `<prefix>/<relative path>`, for the daily
 * export of Android's derived files (`filesDir/derived/v1`). Read with positional reads, no mapping.
 */
fun directoryEntries(root: File, prefix: String): List<ExportEntry> =
    root.walkTopDown().filter { it.isFile }.map { f ->
        val relative = f.relativeTo(root).invariantSeparatorsPath
        ExportEntry("$prefix/$relative", f.lastModified()) { LocalFileReader(f) }
    }.toList()

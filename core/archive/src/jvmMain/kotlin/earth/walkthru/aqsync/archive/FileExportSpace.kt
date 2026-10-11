package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * [ExportSpace] in a JVM directory. Android passes `cacheDir/export`, the folder its `FileProvider`
 * serves, so each run is `export/run-<uuid>/` and a staged file is shareable as a content URI.
 */
class FileExportSpace(private val root: File) : ExportSpace {
    override fun newRun(): ExportRun {
        root.listFiles()?.forEach { it.deleteRecursively() }
        val dir = File(root, "run-" + UUID.randomUUID())
        check(dir.mkdirs()) { "cannot create the export folder" }
        return FileExportRun(dir)
    }

    override fun freeBytes(): Long? = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace
}

private class FileExportRun(private val dir: File) : ExportRun {
    override fun path(name: String): String = file(name).absolutePath

    override fun create(name: String): ExportSink {
        val out = FileOutputStream(file(name)).buffered(64 * 1024)
        return object : ExportSink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = out.close()
        }
    }

    override fun delete() {
        dir.deleteRecursively()
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

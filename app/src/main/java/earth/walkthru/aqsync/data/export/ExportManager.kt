package earth.walkthru.aqsync.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.sync.FileArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ways a verified file leaves the phone, all user-initiated and all local:
 * the system share sheet ([Intent.ACTION_SEND] / [Intent.ACTION_SEND_MULTIPLE]) through a
 * [FileProvider], or "Save to…" via the Storage Access Framework. No network, no cloud SDK.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archive: FileArchive,
) {
    private val authority = "${context.packageName}.files"
    private val exportDir = File(context.cacheDir, "export").apply { mkdirs() }

    /** Parquet has a registered type since 2024; some share targets still prefer octet-stream. */
    val parquetMime = "application/vnd.apache.parquet"
    val zipMime = "application/zip"

    fun uriFor(file: ArchivedFile): Uri? {
        val f = archive.fileFor(file.station, file.name)
        if (!f.isFile) return null
        return FileProvider.getUriForFile(context, authority, f)
    }

    fun shareIntent(file: ArchivedFile): Intent? {
        val uri = uriFor(file) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = parquetMime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name.substringAfterLast('/'))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }.let { Intent.createChooser(it, "Share Parquet file") }
    }

    fun shareIntent(files: List<ArchivedFile>): Intent? {
        val uris = files.mapNotNull(::uriFor)
        if (uris.isEmpty()) return null
        if (uris.size == 1) return shareIntent(files.first { uriFor(it) != null })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = parquetMime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("parquet", uris.first()).also { cd ->
                uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
            }
        }.let { Intent.createChooser(it, "Share ${uris.size} Parquet files") }
    }

    /** Suggested filename for the archive zip. */
    fun zipName(): String {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        return "aq-sync-archive-$ts.zip"
    }

    /**
     * Zip the whole verified archive (Hive paths preserved: `<station>/station=…/year=…/…`) into
     * the cache directory. Returns the zip file or null if the archive is empty.
     */
    suspend fun buildArchiveZip(): File? = withContext(Dispatchers.IO) {
        val entries = archive.listAll()
        if (entries.isEmpty()) return@withContext null
        exportDir.listFiles()?.forEach { it.delete() }
        val out = File(exportDir, zipName())
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // Parquet pages are already compressed on the device; store instead of deflating.
            zip.setLevel(0)
            for ((station, name, file) in entries.sortedBy { it.first + "/" + it.second }) {
                val entry = ZipEntry("$station/$name").apply {
                    time = file.lastModified()
                }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        out
    }

    fun shareZipIntent(zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority, zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = zipMime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(zip.name, uri)
        }.let { Intent.createChooser(it, "Share archive") }
    }

    /** Copy [zip] to a document Uri returned by ACTION_CREATE_DOCUMENT. */
    suspend fun copyTo(zip: File, target: Uri): Long = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target, "wt")?.use { out ->
            zip.inputStream().use { it.copyTo(out) }
        } ?: error("could not open destination")
    }
}

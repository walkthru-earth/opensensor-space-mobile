package earth.walkthru.opensensor.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import earth.walkthru.aqsync.archive.ArchiveExport
import earth.walkthru.aqsync.archive.StagedFile
import earth.walkthru.opensensor.files.resources.Res
import earth.walkthru.opensensor.files.resources.share_archive
import earth.walkthru.opensensor.files.resources.share_file
import earth.walkthru.opensensor.files.resources.share_files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shares through a chooser over the app's `FileProvider` (`<applicationId>.files`, which serves only
 * `cacheDir/export`, the [earth.walkthru.aqsync.archive.FileExportSpace] root) and saves through the
 * Storage Access Framework. The chooser gives no close signal, so [ExportPresenter.share] returns false
 * and the next export removes the run.
 */
@Composable
actual fun rememberExportPresenter(): ExportPresenter {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Uri?>>(1) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveExport.ZIP_MIME)) { uri ->
        pending[0]?.complete(uri)
        pending[0] = null
    }
    return remember(context) {
        object : ExportPresenter {
            override suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean {
                val uris = files.map { uriFor(context, it) }
                val mime = files.first().mime
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                        putExtra(Intent.EXTRA_SUBJECT, files.first().name)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
                }
                send.type = mime
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(files.first().name, uris.first()).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
                val title = when (kind) {
                    ShareKind.FILE -> getString(Res.string.share_file)
                    ShareKind.FILES -> if (files.size == 1) getString(Res.string.share_file) else getPluralString(Res.plurals.share_files, files.size, files.size)
                    ShareKind.ARCHIVE -> getString(Res.string.share_archive)
                }
                val chooser = Intent.createChooser(send, title)
                if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                return false
            }

            override suspend fun save(file: StagedFile): Boolean {
                val result = CompletableDeferred<Uri?>()
                pending[0]?.cancel()
                pending[0] = result
                createDocument.launch(file.name)
                val uri = result.await() ?: return true
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    out.use { o -> File(file.path).inputStream().use { it.copyTo(o) } }
                }
                return true
            }
        }
    }
}

private fun uriFor(context: Context, file: StagedFile): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path))

actual fun formatLocalDay(date: LocalDate): String =
    DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.getDefault()).format(date.toJavaLocalDate())

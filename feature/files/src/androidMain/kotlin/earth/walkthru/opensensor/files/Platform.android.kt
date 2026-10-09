package earth.walkthru.opensensor.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * The save picker's open request. It lives in a ViewModel, so a rotation or a theme change while the
 * picker is open hands the picked document to the same waiting save, whose coroutine outlives the
 * activity. Leaving the screen for good answers it with null, so the export never hangs.
 */
internal class PendingSave : ViewModel() {
    var result: CompletableDeferred<Uri?>? = null

    fun answer(uri: Uri?) {
        result?.complete(uri)
        result = null
    }

    override fun onCleared() = answer(null)
}

/**
 * Shares through a chooser over the app's `FileProvider` (`<applicationId>.files`, which serves only
 * `cacheDir/export`, the [earth.walkthru.aqsync.archive.FileExportSpace] root) and saves through the
 * Storage Access Framework. The chooser gives no close signal, so [ExportPresenter.share] returns false
 * and the next export removes the run.
 */
@Composable
actual fun rememberExportPresenter(): ExportPresenter {
    // The application context, since a save can finish after the activity that opened it is gone.
    val context = LocalContext.current
    val app = context.applicationContext
    val pending = viewModel { PendingSave() }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveExport.ZIP_MIME)) { uri ->
        pending.answer(uri)
    }
    return remember(context, pending) {
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
                pending.answer(null)
                pending.result = result
                createDocument.launch(file.name)
                val uri = result.await() ?: return true
                val resolver = app.contentResolver
                withContext(Dispatchers.IO) {
                    try {
                        val out = resolver.openOutputStream(uri, "wt") ?: error("no output stream")
                        out.use { o -> File(file.path).inputStream().use { it.copyTo(o) } }
                    } catch (e: Throwable) {
                        // The picker made an empty document. Remove it so no half zip is left behind.
                        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        throw e
                    }
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

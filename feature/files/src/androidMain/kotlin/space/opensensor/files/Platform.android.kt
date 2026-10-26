package space.opensensor.files

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import space.opensensor.archive.ArchiveExport
import space.opensensor.archive.ExportSink
import space.opensensor.archive.StagedFile
import space.opensensor.files.resources.Res
import space.opensensor.files.resources.share_archive
import space.opensensor.files.resources.share_file
import space.opensensor.files.resources.share_files
import java.io.File
import java.io.OutputStream
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
 * `cacheDir/export`, the [space.opensensor.archive.FileExportSpace] root) and saves through the
 * Storage Access Framework. The chooser gives no close signal, so [ExportPresenter.share] returns false
 * and the run is released to the age sweep (`ExportRuns.MAX_AGE_MS`). A later export removes it only by that age,
 * so a target that opens its URI late still finds the file. A zip save streams into the picked document with no staged copy,
 * a share stays staged since its targets read a finished file by URI after the chooser returns.
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

            // True even when dismissed, nothing reads the staged copy once the picker closed.
            override suspend fun save(file: StagedFile): Boolean {
                pickAndWrite(file.name) { out -> File(file.path).inputStream().use { it.copyTo(out) } }
                return true
            }

            override suspend fun saveStreaming(name: String, write: suspend (ExportSink) -> Unit): Boolean =
                pickAndWrite(name) { out ->
                    val sink = object : ExportSink {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                        override fun close() = Unit
                    }
                    write(sink)
                }

            /**
             * Opens the picker for [name] and runs [write] on the picked document. Returns false when
             * the picker was dismissed. A failed write removes the document, so no half zip is left.
             */
            private suspend fun pickAndWrite(name: String, write: suspend (OutputStream) -> Unit): Boolean {
                val result = CompletableDeferred<Uri?>()
                pending.answer(null)
                pending.result = result
                createDocument.launch(name)
                val uri = result.await() ?: return false
                val resolver = app.contentResolver
                withContext(Dispatchers.IO) {
                    try {
                        val out = resolver.openOutputStream(uri, "wt") ?: error("no output stream")
                        out.buffered(64 * 1024).use { write(it) }
                    } catch (e: Throwable) {
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

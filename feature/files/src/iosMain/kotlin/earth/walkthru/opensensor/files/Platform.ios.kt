@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.opensensor.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import earth.walkthru.aqsync.archive.StagedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

/**
 * Shares with `UIActivityViewController` and saves with `UIDocumentPickerViewController(forExporting:
 * asCopy:)`, both over the staged copy in the temporary directory, never the archive file. Each call
 * returns when the sheet closed, so the controller removes the run right away.
 */
@Composable
actual fun rememberExportPresenter(): ExportPresenter = remember { IosExportPresenter }

private object IosExportPresenter : ExportPresenter {
    /** The picker holds its delegate weakly. */
    private val delegates = mutableSetOf<NSObject>()

    override suspend fun share(files: List<StagedFile>, kind: ShareKind): Boolean {
        val top = topController() ?: return true
        val closed = CompletableDeferred<Unit>()
        val sheet = UIActivityViewController(activityItems = files.map { NSURL.fileURLWithPath(it.path) }, applicationActivities = null)
        sheet.completionWithItemsHandler = { _, _, _, _ -> closed.complete(Unit) }
        anchor(sheet, top)
        top.presentViewController(sheet, animated = true, completion = null)
        closed.await()
        return true
    }

    override suspend fun save(file: StagedFile): Boolean {
        val top = topController() ?: return true
        val closed = CompletableDeferred<Unit>()
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(file.path)), asCopy = true)
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                closed.complete(Unit)
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                closed.complete(Unit)
            }
        }
        delegates += delegate
        picker.delegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
        try {
            closed.await()
        } finally {
            delegates -= delegate
        }
        return true
    }

    /** iPad shows the sheet as a popover, which needs a source. */
    private fun anchor(sheet: UIViewController, top: UIViewController) {
        val popover = sheet.popoverPresentationController ?: return
        val view = top.view
        popover.sourceView = view
        popover.sourceRect = view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        popover.permittedArrowDirections = 0u
    }

    private fun topController(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .let { windows -> windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() }
        var vc = window?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }
}

actual fun formatLocalDay(date: LocalDate): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.currentLocale
    formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    formatter.dateFormat = "EEE d MMM y"
    val seconds = date.atStartOfDayIn(TimeZone.UTC).epochSeconds
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(seconds.toDouble()))
}

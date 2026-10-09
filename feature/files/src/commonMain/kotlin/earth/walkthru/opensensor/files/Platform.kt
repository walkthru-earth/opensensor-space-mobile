package earth.walkthru.opensensor.files

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate

/**
 * The platform's share sheet and save picker for staged exports. Android uses a chooser over its
 * `FileProvider` and the `CreateDocument` picker, iOS `UIActivityViewController` and
 * `UIDocumentPickerViewController(forExporting:asCopy:)`.
 */
@Composable
expect fun rememberExportPresenter(): ExportPresenter

/** A group header day, `Fri 18 Sep 2026` in the phone's language. */
expect fun formatLocalDay(date: LocalDate): String

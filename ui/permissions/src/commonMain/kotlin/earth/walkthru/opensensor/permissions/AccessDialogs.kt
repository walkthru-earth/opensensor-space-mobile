package earth.walkthru.opensensor.permissions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The words of one permission's two in-context dialogs. Short, one line each where possible
 * (minimal copy). No dashes, colons or semicolons. Android builds these from `strings.xml`.
 */
@Immutable
data class AccessDialogTexts(
    val rationaleTitle: String,
    val rationaleBody: String,
    val settingsTitle: String,
    val settingsBody: String,
    val continueLabel: String = "Continue",
    val notNow: String = "Not now",
    val openSettings: String = "Open settings",
    val cancel: String = "Cancel",
    val icon: ImageVector? = null,
)

/** The rationale and the settings dialog for [access]. Shown only when its flow asks. */
@Composable
fun AccessDialogs(access: RuntimeAccess, texts: AccessDialogTexts) {
    val icon: (@Composable () -> Unit)? = texts.icon?.let { vector -> { Icon(vector, contentDescription = null) } }
    when (access.dialog) {
        AccessDialog.RATIONALE -> AlertDialog(
            onDismissRequest = access::dismiss,
            icon = icon,
            title = { Text(texts.rationaleTitle) },
            text = { Text(texts.rationaleBody) },
            confirmButton = { TextButton(onClick = access::confirmRationale) { Text(texts.continueLabel) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(texts.notNow) } },
        )
        AccessDialog.SETTINGS -> AlertDialog(
            onDismissRequest = access::dismiss,
            icon = icon,
            title = { Text(texts.settingsTitle) },
            text = { Text(texts.settingsBody) },
            confirmButton = { TextButton(onClick = access::openSettings) { Text(texts.openSettings) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(texts.cancel) } },
        )
        null -> Unit
    }
}

/**
 * A small link to the app's page in system settings, for a screen that shows a denial inline
 * (iOS Bluetooth and Local Network). Show it only when [PermissionFlow.offersSettings] is true.
 */
@Composable
fun SettingsLink(label: String, modifier: Modifier = Modifier) {
    val open = rememberOpenAppSettings()
    TextButton(onClick = open, modifier = modifier) { Text(label) }
}

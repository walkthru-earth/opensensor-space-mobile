package earth.walkthru.opensensor.permissions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.action_cancel
import earth.walkthru.opensensor.strings.resources.action_not_now
import earth.walkthru.opensensor.strings.resources.perm_continue
import earth.walkthru.opensensor.strings.resources.perm_open_settings
import org.jetbrains.compose.resources.stringResource

/**
 * The words of one permission's two in-context dialogs. Short, one line each where possible
 * (minimal copy). No dashes, colons or semicolons. Android builds these from `strings.xml`.
 * A button label left null reads the shared `:ui:strings` catalogue, same keys and words as Android.
 */
@Immutable
data class AccessDialogTexts(
    val rationaleTitle: String,
    val rationaleBody: String,
    val settingsTitle: String,
    val settingsBody: String,
    val continueLabel: String? = null,
    val notNow: String? = null,
    val openSettings: String? = null,
    val cancel: String? = null,
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
            title = { Text(texts.rationaleTitle, Modifier.semantics { heading() }) },
            text = { Text(texts.rationaleBody) },
            confirmButton = { TextButton(onClick = access::confirmRationale) { Text(texts.continueLabel ?: stringResource(Res.string.perm_continue)) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(texts.notNow ?: stringResource(Res.string.action_not_now)) } },
        )
        AccessDialog.SETTINGS -> AlertDialog(
            onDismissRequest = access::dismiss,
            icon = icon,
            title = { Text(texts.settingsTitle, Modifier.semantics { heading() }) },
            text = { Text(texts.settingsBody) },
            confirmButton = { TextButton(onClick = access::openSettings) { Text(texts.openSettings ?: stringResource(Res.string.perm_open_settings)) } },
            dismissButton = { TextButton(onClick = access::dismiss) { Text(texts.cancel ?: stringResource(Res.string.action_cancel)) } },
        )
        null -> Unit
    }
}

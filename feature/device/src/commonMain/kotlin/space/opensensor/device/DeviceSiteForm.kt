package space.opensensor.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.PillButton
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.site_deployment
import space.opensensor.device.resources.site_description
import space.opensensor.device.resources.site_error_control
import space.opensensor.device.resources.site_error_deployment_charset
import space.opensensor.device.resources.site_error_deployment_reserved
import space.opensensor.device.resources.site_error_encoding
import space.opensensor.device.resources.site_error_scheme
import space.opensensor.device.resources.site_error_too_long
import space.opensensor.device.resources.site_error_url_charset
import space.opensensor.device.resources.site_error_user_info
import space.opensensor.device.resources.site_failed
import space.opensensor.device.resources.site_name
import space.opensensor.device.resources.site_not_confirmed
import space.opensensor.device.resources.site_provider
import space.opensensor.device.resources.site_read_failed
import space.opensensor.device.resources.site_retry
import space.opensensor.device.resources.site_save
import space.opensensor.device.resources.site_saved
import space.opensensor.device.resources.site_url
import space.opensensor.model.SiteField
import space.opensensor.model.SiteProblem

/** Whether the site form may send now. The wizard and the settings card both gate Save on this. */
val DeviceConfigUiState.canSaveSite: Boolean get() = canEdit && !snapshot.syncRunning && site.canSave

/**
 * The owner site fields (firmware #148), shared by the Device settings card and the setup wizard.
 * Typing only drafts. Nothing reaches the sensor until Save, and Save sends the changed keys in
 * their own `SET_CONFIG`. A value the board would refuse shows its reason under the field and keeps
 * Save off. The form is shown only when the sensor answered page 5.
 *
 * [showSave] puts the Save button in the form. The wizard leaves it out and saves from its own
 * primary button.
 */
@Composable
fun DeviceSiteForm(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit, showSave: Boolean) {
    val site = state.site
    val problems = site.problems
    val enabled = state.canEdit && site.supported == true && !site.working
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SiteField.entries.forEach { field ->
            SiteTextField(field, site.draft[field].orEmpty(), problems[field], enabled, onAction)
        }
        if (showSave) {
            PillButton(stringResource(Res.string.site_save), onClick = { onAction(DeviceConfigAction.SaveSite) }, enabled = state.canSaveSite)
        }
        when (val result = site.result) {
            null -> Unit
            DeviceSiteResult.READ_FAILED -> SiteReadFailed(state, onAction)
            else -> Text(
                stringResource(
                    when (result) {
                        DeviceSiteResult.SAVED -> Res.string.site_saved
                        DeviceSiteResult.NOT_CONFIRMED -> Res.string.site_not_confirmed
                        else -> Res.string.site_failed
                    }
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Pages 5 and 6 could not be read. Says so and offers a new read of those two pages only, which
 * sends nothing to the board. The wizard and the settings card show the same.
 */
@Composable
internal fun SiteReadFailed(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    Text(stringResource(Res.string.site_read_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    TextButton(enabled = state.ready && state.isV2 && !state.site.working, onClick = { onAction(DeviceConfigAction.RetrySite) }) {
        Text(stringResource(Res.string.site_retry))
    }
}

@Composable
private fun SiteTextField(field: SiteField, value: String, problem: SiteProblem?, enabled: Boolean, onAction: (DeviceConfigAction) -> Unit) {
    val multiLine = field == SiteField.DESCRIPTION
    OutlinedTextField(
        value = value,
        // The board keeps one line. Enter in the description becomes a space instead of a refusal.
        onValueChange = { typed -> onAction(DeviceConfigAction.SiteValue(field, if (multiLine) typed.replace(LINE_BREAK, " ") else typed)) },
        label = { Text(stringResource(labelOf(field))) },
        isError = problem != null,
        supportingText = problem?.let { p ->
            {
                Text(
                    if (p == SiteProblem.TOO_LONG) stringResource(Res.string.site_error_too_long, field.maxBytes) else stringResource(messageOf(field, p)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        singleLine = !multiLine,
        maxLines = if (multiLine) 4 else 1,
        keyboardOptions = keyboardOf(field),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val LINE_BREAK = Regex("\r\n|[\r\n]")

private fun labelOf(field: SiteField): StringResource = when (field) {
    SiteField.TITLE -> Res.string.site_name
    SiteField.PROVIDER -> Res.string.site_provider
    SiteField.URL -> Res.string.site_url
    SiteField.DESCRIPTION -> Res.string.site_description
    SiteField.DEPLOYMENT_ID -> Res.string.site_deployment
}

private fun keyboardOf(field: SiteField): KeyboardOptions = when (field) {
    SiteField.URL -> KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    SiteField.DEPLOYMENT_ID -> KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    SiteField.TITLE, SiteField.PROVIDER, SiteField.DESCRIPTION -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
}

/** The reason under a field. [SiteProblem.TOO_LONG] carries the cap and is formatted by the caller. */
private fun messageOf(field: SiteField, problem: SiteProblem): StringResource = when (problem) {
    SiteProblem.TOO_LONG -> Res.string.site_error_too_long
    SiteProblem.CONTROL -> Res.string.site_error_control
    SiteProblem.ENCODING -> Res.string.site_error_encoding
    SiteProblem.SCHEME -> Res.string.site_error_scheme
    SiteProblem.USER_INFO -> Res.string.site_error_user_info
    SiteProblem.CHARSET -> if (field == SiteField.DEPLOYMENT_ID) Res.string.site_error_deployment_charset else Res.string.site_error_url_charset
    SiteProblem.RESERVED -> Res.string.site_error_deployment_reserved
}

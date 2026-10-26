package space.opensensor.permissions

import androidx.compose.runtime.Composable

/**
 * Opens this app's page in the system settings, where the user can change any permission.
 * Android uses the app details screen (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`), iOS
 * `UIApplication.openSettingsURLString`. Call the returned function from a tap only.
 */
@Composable
expect fun rememberOpenAppSettings(): () -> Unit

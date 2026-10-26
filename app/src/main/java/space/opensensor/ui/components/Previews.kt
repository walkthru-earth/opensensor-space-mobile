package space.opensensor.ui.components

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Light and dark phone previews on the brand theme. Pair with a Simple and a Developer variant. */
@Preview(name = "Light", showBackground = true, widthDp = 380, heightDp = 1400)
@Preview(name = "Dark", showBackground = true, widthDp = 380, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ThemePreviews

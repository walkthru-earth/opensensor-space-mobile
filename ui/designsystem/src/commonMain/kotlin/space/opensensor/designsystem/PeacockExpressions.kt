package space.opensensor.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.peacock
import space.opensensor.designsystem.resources.peacock_calm
import space.opensensor.designsystem.resources.peacock_concerned
import space.opensensor.designsystem.resources.peacock_confused
import space.opensensor.designsystem.resources.peacock_feather_shield
import space.opensensor.designsystem.resources.peacock_happy
import space.opensensor.designsystem.resources.peacock_masked
import space.opensensor.designsystem.resources.peacock_sleeping
import space.opensensor.designsystem.resources.peacock_working
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep

/** One reference character, with compact portraits for small icons and full artwork for welcome. */
enum class PeacockExpression {
    WELCOME, HAPPY, CALM, CONCERNED, MASKED, FEATHER_SHIELD, CONFUSED, SLEEPING, WORKING;

    internal val fileName: String
        get() = if (this == WELCOME) "peacock.webp" else "peacock_${name.lowercase()}.webp"

    internal val drawable: DrawableResource
        get() = when (this) {
            WELCOME -> Res.drawable.peacock
            HAPPY -> Res.drawable.peacock_happy
            CALM -> Res.drawable.peacock_calm
            CONCERNED -> Res.drawable.peacock_concerned
            MASKED -> Res.drawable.peacock_masked
            FEATHER_SHIELD -> Res.drawable.peacock_feather_shield
            CONFUSED -> Res.drawable.peacock_confused
            SLEEPING -> Res.drawable.peacock_sleeping
            WORKING -> Res.drawable.peacock_working
        }
}

/** Visual mapping only. The metric registry continues to own the scale, labels and thresholds. */
fun peacockExpressionFor(step: WhoStep?): PeacockExpression = when (step) {
    WhoStep.GUIDELINE -> PeacockExpression.HAPPY
    WhoStep.IT4 -> PeacockExpression.CALM
    WhoStep.IT3 -> PeacockExpression.CONCERNED
    WhoStep.IT2, WhoStep.IT1 -> PeacockExpression.MASKED
    WhoStep.ABOVE_IT1 -> PeacockExpression.FEATHER_SHIELD
    null -> PeacockExpression.CONFUSED
}

/** The same mapping for a sensor's mood, through its step. */
fun peacockExpressionFor(mood: AirMood?): PeacockExpression = peacockExpressionFor(mood?.step)

/** Static full-colour icon. Give small portraits at least 48 dp and retain the adjacent state text. */
@Composable
fun PeacockIcon(expression: PeacockExpression, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painter = painterResource(expression.drawable),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** The body rests on a small card while the head, crest and wing gesture rise above its edge. */
@Composable
fun PeacockPortrait(expression: PeacockExpression, modifier: Modifier = Modifier, size: Dp = 176.dp) {
    Box(modifier.size(size).graphicsLayer { rotationZ = -5f }, contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(size * 0.54f)
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        // Only the backplate is clipped. The transparent character is a separate, full-size layer.
        // Portrait exports have a 16 px margin on a 512 px canvas. Move that transparent bottom
        // margin past the panel edge, so the visible body rests flush with the white backplate.
        PeacockIcon(expression, Modifier.fillMaxSize().offset(y = size / 32f))
    }
}

/** Replaces generic mood faces in live readings and archive summaries on both platforms. */
@Composable
fun AirQualityPeacock(mood: AirMood?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    PeacockIcon(peacockExpressionFor(mood), modifier, contentDescription)
}

/** Bundled resource URI for platform adapters, including Android notification large icons. */
@OptIn(ExperimentalResourceApi::class)
fun peacockResourceUri(expression: PeacockExpression): String = Res.getUri("drawable/${expression.fileName}")

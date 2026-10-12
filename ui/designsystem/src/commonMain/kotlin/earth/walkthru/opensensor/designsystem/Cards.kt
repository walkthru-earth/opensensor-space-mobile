package earth.walkthru.opensensor.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How loud a card is. Hero is the one card per screen that carries the answer. */
enum class CardTone { PLAIN, SOFT, HERO }

/**
 * The website's rounded panel (`.brand-panel`, 2rem corners, thin border) as a card, on the one
 * content card radius [CardShape]. [CardTone.HERO] sits on the soft brand blue, [CardTone.SOFT] on
 * the surface tint, [CardTone.PLAIN] on the low container with a thin outline.
 */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.PLAIN,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (tone) {
        CardTone.HERO -> scheme.primaryContainer to scheme.onPrimaryContainer
        CardTone.SOFT -> scheme.surfaceContainerHigh to scheme.onSurface
        CardTone.PLAIN -> scheme.surfaceContainerLow to scheme.onSurface
    }
    Surface(
        shape = CardShape,
        color = container,
        contentColor = onContainer,
        border = if (tone == CardTone.PLAIN) BorderStroke(1.dp, scheme.outlineVariant) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Card wrapper used by the Device and Debug screens: an eyebrow title, an optional trailing control, then the body. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(title, heading = true)
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * The OpenSensor peacock, with reference-matched expressions for readings and app states.
 * Pass a [contentDescription] only when the character says something the text beside it does not.
 */
@Composable
fun Peacock(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    contentDescription: String? = null,
    expression: PeacockExpression = PeacockExpression.WELCOME,
    animate: Boolean = true,
) {
    if (!animate) {
        PeacockIcon(expression, modifier.size(size), contentDescription)
        return
    }
    // A finite, gentle entrance. Compose scales animation by the system animator setting, so
    // "Remove animations" shows the bird immediately.
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.92f),
        modifier = modifier,
    ) {
        PeacockIcon(expression, Modifier.size(size), contentDescription)
    }
}

/**
 * The mascot as a guide: the bird, a short title and one or two plain sentences, and at most one
 * action. Used for "no devices yet", "nothing nearby" and "all synced" moments.
 */
@Composable
fun MascotGuide(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    expression: PeacockExpression = PeacockExpression.CONFUSED,
    action: (@Composable () -> Unit)? = null,
) {
    BrandCard(modifier = modifier, tone = CardTone.SOFT, contentPadding = PaddingValues(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Peacock(size = 96.dp, expression = expression)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** A warning strip inside a card. */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An informational strip inside a card. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

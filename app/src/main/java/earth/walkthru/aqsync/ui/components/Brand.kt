package earth.walkthru.aqsync.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.ui.theme.color
import earth.walkthru.aqsync.ui.theme.onColor
import earth.walkthru.opensensor.designsystem.BrandTokens
import earth.walkthru.opensensor.designsystem.FloatingControl

/** How loud a card is. Hero is the one card per screen that carries the answer. */
enum class CardTone { PLAIN, SOFT, HERO }

/**
 * The website's rounded panel (`.brand-panel`, 2rem corners, thin border) as a card. [CardTone.HERO]
 * sits on the soft brand blue, [CardTone.SOFT] on the surface tint, [CardTone.PLAIN] on the low
 * container with a thin outline.
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
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = onContainer,
        border = if (tone == CardTone.PLAIN) BorderStroke(1.dp, scheme.outlineVariant) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * The primary action: a Signal blue curved card with ink text and a trailing arrow. One per screen.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrow: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = FloatingControl.Shape,
        colors = FloatingControl.primaryButtonColors(),
        contentPadding = PaddingValues(start = 24.dp, end = if (arrow) 18.dp else 24.dp, top = 14.dp, bottom = 14.dp),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
        if (arrow) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** A quieter outlined card for the secondary action next to a [PillButton], same curve. */
@Composable
fun OutlinePillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = FloatingControl.Shape,
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The OpenSensor peacock, the website's mascot (`public/mascots/peacock.webp`). An illustrated
 * guide for welcome, empty and success states. Never next to a chart or a number: it is not data.
 * Pass a [contentDescription] only when the character says something the text beside it does not.
 */
@Composable
fun Peacock(modifier: Modifier = Modifier, size: Dp = 120.dp, contentDescription: String? = null) {
    // A finite, gentle entrance. Compose scales animation by the system animator setting, so
    // "Remove animations" shows the bird immediately.
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.92f),
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(R.drawable.peacock),
            contentDescription = contentDescription,
            modifier = Modifier.size(size),
        )
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
    action: (@Composable () -> Unit)? = null,
) {
    BrandCard(modifier = modifier, tone = CardTone.SOFT, contentPadding = PaddingValues(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Peacock(size = 96.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
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

/** The website hero's bottom stripe (`.brand-hero-art`): main, accent, deep, surface, main. */
@Composable
fun BrandStripe(modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val p = if (dark) BrandTokens.Dark else BrandTokens.Light
    Row(modifier.fillMaxWidth().height(height).clip(CircleShape)) {
        listOf(p.main, p.accent, p.deep, p.surface, p.main).forEach { c ->
            Box(Modifier.weight(1f).height(height).background(c))
        }
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * A badge tilted like the website's `.brand-icon` (rotate -5deg, rounded square). Only for the
 * hero card's character, never for numbers or charts.
 */
@Composable
fun TiltedBadge(color: Color, modifier: Modifier = Modifier, size: Dp = 88.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = -5f }
            .clip(MaterialTheme.shapes.large)
            .background(color),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The mood name on its own WHO step colour, with ink chosen for contrast (`AirMood.onColor`). A small curved card. */
@Composable
fun MoodPill(mood: AirMood, modifier: Modifier = Modifier) {
    Surface(shape = FloatingControl.SmallShape, color = mood.color(), contentColor = mood.onColor(), modifier = modifier) {
        Text(
            mood.titleText(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

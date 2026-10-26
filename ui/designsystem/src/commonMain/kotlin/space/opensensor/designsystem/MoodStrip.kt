package space.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.airquality.MoodBlock
import space.opensensor.airquality.WhoStep
import space.opensensor.designsystem.resources.Res
import space.opensensor.designsystem.resources.list_separator
import space.opensensor.designsystem.resources.mood_fair
import space.opensensor.designsystem.resources.mood_fair_line
import space.opensensor.designsystem.resources.mood_fresh
import space.opensensor.designsystem.resources.mood_fresh_line
import space.opensensor.designsystem.resources.mood_hazy
import space.opensensor.designsystem.resources.mood_hazy_line
import space.opensensor.designsystem.resources.mood_none
import space.opensensor.designsystem.resources.mood_none_line
import space.opensensor.designsystem.resources.mood_poor
import space.opensensor.designsystem.resources.mood_poor_line
import space.opensensor.designsystem.resources.mood_smoky
import space.opensensor.designsystem.resources.mood_smoky_line
import space.opensensor.designsystem.resources.mood_strip_selected
import space.opensensor.designsystem.resources.mood_thick
import space.opensensor.designsystem.resources.mood_thick_line
import space.opensensor.designsystem.resources.value_not_available

internal fun moodWordRes(step: WhoStep?): StringResource = when (step) {
    WhoStep.GUIDELINE -> Res.string.mood_fresh
    WhoStep.IT4 -> Res.string.mood_fair
    WhoStep.IT3 -> Res.string.mood_hazy
    WhoStep.IT2 -> Res.string.mood_poor
    WhoStep.IT1 -> Res.string.mood_smoky
    WhoStep.ABOVE_IT1 -> Res.string.mood_thick
    null -> Res.string.mood_none
}

/** The one-line meaning of [step], for screens that pick a resource by rule. Null is "No reading". */
fun moodLineRes(step: WhoStep?): StringResource = when (step) {
    WhoStep.GUIDELINE -> Res.string.mood_fresh_line
    WhoStep.IT4 -> Res.string.mood_fair_line
    WhoStep.IT3 -> Res.string.mood_hazy_line
    WhoStep.IT2 -> Res.string.mood_poor_line
    WhoStep.IT1 -> Res.string.mood_smoky_line
    WhoStep.ABOVE_IT1 -> Res.string.mood_thick_line
    null -> Res.string.mood_none_line
}

/** The mood word of [step], one vocabulary for every screen. Null is "No reading". */
@Composable
fun moodWord(step: WhoStep?): String = stringResource(moodWordRes(step))

/** One plain sentence for [step]. It never names the guideline body, so Simple can show it. */
@Composable
fun moodLine(step: WhoStep?): String = stringResource(moodLineRes(step))

/** A missing value, "n/a". Never zero and never a dash. */
@Composable
fun notAvailableText(): String = stringResource(Res.string.value_not_available)

/**
 * The list comma of the current language, "," in English and "،" in Arabic. Spoken lists join
 * with it and one space, [spokenList].
 */
@Composable
fun listSeparator(): String = stringResource(Res.string.list_separator)

/** [items] joined with [separator] and one space, the way a screen reader reads a list. */
fun spokenList(items: List<String>, separator: String): String = items.joinToString("$separator ")

/** "word caption" per block, joined with the list comma. Captions are optional per block. */
internal fun moodSummary(words: List<String>, captions: List<String?>, separator: String = ","): String =
    spokenList(words.indices.map { i -> blockLabel(words[i], captions.getOrNull(i)) }, separator)

/** One block read aloud, its word then its caption. */
internal fun blockLabel(word: String, caption: String?): String = listOfNotNull(word, caption).joinToString(" ")

/** The legend read as one sentence, the title then the six words. */
internal fun legendSummary(words: List<String>, title: String?, separator: String = ","): String =
    listOfNotNull(title, spokenList(words, separator)).joinToString(". ")

/**
 * Mood blocks side by side, widths by [MoodBlock.weight], no axis. A null step is hatched and read
 * "n/a". The row follows the layout direction, so it mirrors in right to left. It is one
 * accessibility node that reads every block with its caption. With [onBlock], each block is also a
 * button and a custom action, [selected] gets an outline and is announced as the strip's state,
 * and the strip is at least 48 dp tall to touch while the bar stays 28 dp.
 */
@Composable
fun MoodStrip(
    blocks: List<MoodBlock>,
    modifier: Modifier = Modifier,
    captions: List<String?> = emptyList(),
    selected: Int? = null,
    onBlock: ((Int) -> Unit)? = null,
) {
    val na = notAvailableText()
    val words = blocks.map { b ->
        val step = b.step
        if (step == null) na else moodWord(step)
    }
    val chosen = selected?.takeIf { it in blocks.indices && blocks[it].weight > 0f }
    val selectedSpoken = chosen?.let { stringResource(Res.string.mood_strip_selected, blockLabel(words[it], captions.getOrNull(it))) }
    MoodStripLayout(blocks, words, captions, selected, selectedSpoken, onBlock, modifier, listSeparator())
}

@Composable
internal fun MoodStripLayout(
    blocks: List<MoodBlock>,
    words: List<String>,
    captions: List<String?>,
    selected: Int?,
    selectedSpoken: String?,
    onBlock: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    separator: String = ",",
) {
    val shown = blocks.indices.filter { blocks[it].weight > 0f }
    val summary = moodSummary(shown.map { words[it] }, shown.map { captions.getOrNull(it) }, separator)
    val captioned = shown.any { captions.getOrNull(it) != null }
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.onSurface
    val hatch = scheme.outline
    val track = scheme.surfaceContainerHighest
    val muted = scheme.onSurfaceVariant
    BoxWithConstraints(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = summary
            if (selectedSpoken != null) stateDescription = selectedSpoken
            if (onBlock != null) {
                customActions = shown.map { i ->
                    CustomAccessibilityAction(blockLabel(words[i], captions.getOrNull(i))) {
                        onBlock(i)
                        true
                    }
                }
            }
        },
    ) {
        // A tappable block is at least 48 dp wide and tall. The rest keep their weights.
        val available = maxWidth.value - GAP.value * (shown.size - 1).coerceAtLeast(0)
        val widths = blockWidths(shown.map { blocks[it].weight }, available, if (onBlock != null) 48f else 0f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            shown.forEachIndexed { k, i ->
                val block = blocks[i]
                val shape = RoundedCornerShape(6.dp)
                var bar = Modifier.fillMaxWidth().height(28.dp).clip(shape)
                val step = block.step
                bar = if (step != null) {
                    bar.background(step.color())
                } else {
                    bar.background(track).drawBehind {
                        val gap = 6.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(hatch, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                            x += gap
                        }
                    }
                }
                if (i == selected) bar = bar.border(2.dp, outline, shape)
                // The bar and its caption are one target, the whole column takes the tap.
                var target = Modifier.width(widths[k].dp)
                if (onBlock != null) target = target.heightIn(min = 48.dp).clickable(role = Role.Button) { onBlock(i) }
                Column(target, verticalArrangement = Arrangement.Center) {
                    Box(bar)
                    if (captioned) {
                        // A 48 dp block fits "128 areas" on one line, or wraps it between the words.
                        FitText(
                            captions.getOrNull(i).orEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private val GAP = 3.dp

/** The width of each block when the strip has no width to share, inside a horizontal scroll. */
private const val UNBOUNDED_BLOCK = 64f

/**
 * Block widths in [available], proportional to [weights], each at least [min]. A block whose share
 * falls under [min] is lifted to it and the others share what is left. When [min] cannot fit for
 * every block, they share the width equally. An unbounded [available] gives each block a fixed
 * [UNBOUNDED_BLOCK] width, or [min] when that is wider.
 */
internal fun blockWidths(weights: List<Float>, available: Float, min: Float): List<Float> {
    val n = weights.size
    if (n == 0) return emptyList()
    if (!available.isFinite()) return List(n) { maxOf(UNBOUNDED_BLOCK, min) }
    if (min * n >= available) return List(n) { available / n }
    val fixed = BooleanArray(n)
    while (true) {
        val freeWeight = weights.indices.filter { !fixed[it] }.sumOf { weights[it].toDouble() }.toFloat()
        val freeSpace = available - min * fixed.count { it }
        val lifted = weights.indices.filter { !fixed[it] && weights[it] * freeSpace / freeWeight < min }
        if (lifted.isEmpty()) return weights.indices.map { if (fixed[it]) min else weights[it] * freeSpace / freeWeight }
        lifted.forEach { fixed[it] = true }
    }
}

/** The six mood words with their colours, in two columns. One accessibility node. */
@Composable
fun MoodLegend(modifier: Modifier = Modifier, title: String? = null) {
    val words = WhoStep.entries.map { moodWord(it) }
    MoodLegendLayout(words, title, modifier, listSeparator())
}

@Composable
internal fun MoodLegendLayout(words: List<String>, title: String?, modifier: Modifier = Modifier, separator: String = ",") {
    val summary = legendSummary(words, title, separator)
    val swatchBorder = swatchBorderColor(MaterialTheme.colorScheme)
    val swatchShape = RoundedCornerShape(3.dp)
    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        if (title != null) {
            FitText(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
        }
        WhoStep.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { step ->
                    Row(Modifier.weight(1f).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(swatchShape)
                                .background(step.color())
                                .border(1.dp, swatchBorder, swatchShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        FitText(words.getOrElse(step.ordinal) { "" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

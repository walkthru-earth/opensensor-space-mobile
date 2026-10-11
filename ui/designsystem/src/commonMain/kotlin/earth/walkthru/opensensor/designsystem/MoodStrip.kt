package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.airquality.MoodBlock
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.opensensor.designsystem.resources.Res
import earth.walkthru.opensensor.designsystem.resources.mood_fair
import earth.walkthru.opensensor.designsystem.resources.mood_fair_line
import earth.walkthru.opensensor.designsystem.resources.mood_fresh
import earth.walkthru.opensensor.designsystem.resources.mood_fresh_line
import earth.walkthru.opensensor.designsystem.resources.mood_hazy
import earth.walkthru.opensensor.designsystem.resources.mood_hazy_line
import earth.walkthru.opensensor.designsystem.resources.mood_none
import earth.walkthru.opensensor.designsystem.resources.mood_none_line
import earth.walkthru.opensensor.designsystem.resources.mood_poor
import earth.walkthru.opensensor.designsystem.resources.mood_poor_line
import earth.walkthru.opensensor.designsystem.resources.mood_smoky
import earth.walkthru.opensensor.designsystem.resources.mood_smoky_line
import earth.walkthru.opensensor.designsystem.resources.mood_thick
import earth.walkthru.opensensor.designsystem.resources.mood_thick_line
import earth.walkthru.opensensor.designsystem.resources.value_not_available
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun moodWordRes(step: WhoStep?): StringResource = when (step) {
    WhoStep.GUIDELINE -> Res.string.mood_fresh
    WhoStep.IT4 -> Res.string.mood_fair
    WhoStep.IT3 -> Res.string.mood_hazy
    WhoStep.IT2 -> Res.string.mood_poor
    WhoStep.IT1 -> Res.string.mood_smoky
    WhoStep.ABOVE_IT1 -> Res.string.mood_thick
    null -> Res.string.mood_none
}

internal fun moodLineRes(step: WhoStep?): StringResource = when (step) {
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

/** "word caption" per block, joined with commas. Captions are optional per block. */
internal fun moodSummary(words: List<String>, captions: List<String?>): String =
    words.indices.joinToString(", ") { i -> listOfNotNull(words[i], captions.getOrNull(i)).joinToString(" ") }

/** The legend read as one sentence, the title then the six words. */
internal fun legendSummary(words: List<String>, title: String?): String =
    listOfNotNull(title, words.joinToString(", ")).joinToString(". ")

/**
 * Mood blocks side by side, widths by [MoodBlock.weight], no axis. A null step is hatched and read
 * "n/a". The row follows the layout direction, so it mirrors in right to left. It is one
 * accessibility node that reads every block with its caption. With [onBlock], each block is also a
 * button and a custom action, and [selected] gets an outline.
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
    MoodStripLayout(blocks, words, captions, selected, onBlock, modifier)
}

@Composable
internal fun MoodStripLayout(
    blocks: List<MoodBlock>,
    words: List<String>,
    captions: List<String?>,
    selected: Int?,
    onBlock: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shown = blocks.indices.filter { blocks[it].weight > 0f }
    val summary = moodSummary(shown.map { words[it] }, shown.map { captions.getOrNull(it) })
    val outline = MaterialTheme.colorScheme.onSurface
    val hatch = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics {
            contentDescription = summary
            if (onBlock != null) {
                customActions = shown.map { i ->
                    CustomAccessibilityAction(listOfNotNull(words[i], captions.getOrNull(i)).joinToString(" ")) {
                        onBlock(i)
                        true
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shown.forEach { i ->
            val block = blocks[i]
            val shape = RoundedCornerShape(6.dp)
            var m = Modifier.weight(block.weight).fillMaxHeight().clip(shape)
            val step = block.step
            m = if (step != null) {
                m.background(Color(step.color))
            } else {
                m.background(track).drawBehind {
                    val gap = 6.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(hatch, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                        x += gap
                    }
                }
            }
            if (i == selected) m = m.border(2.dp, outline, shape)
            if (onBlock != null) m = m.clickable(role = Role.Button) { onBlock(i) }
            Box(m)
        }
    }
}

/** The six mood words with their colours, in two columns. One accessibility node. */
@Composable
fun MoodLegend(modifier: Modifier = Modifier, title: String? = null) {
    val words = WhoStep.entries.map { moodWord(it) }
    MoodLegendLayout(words, title, modifier)
}

@Composable
internal fun MoodLegendLayout(words: List<String>, title: String?, modifier: Modifier = Modifier) {
    val summary = legendSummary(words, title)
    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
        }
        WhoStep.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { step ->
                    Row(Modifier.weight(1f).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(step.color)))
                        Spacer(Modifier.width(6.dp))
                        Text(words.getOrElse(step.ordinal) { "" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

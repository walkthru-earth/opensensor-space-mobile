package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * How far a label may shrink, as fractions of its own size at font scale 1.0 (docs/cross-platform-guide.md,
 * "Text scaling and wrapping"). The 1.0 size of a display numeral is its token size (84, 48, 32), not the
 * clamped size [brandTypography] gives it for the current scale (see [oneScaleSize]).
 *
 * 1. A label first tries every size from the user's scaled size down to its 1.0 size on one line.
 * 2. If it does not fit at its 1.0 size, it may shrink on one line to [FloorFraction] of that size.
 * 3. Below that it wraps at [FloorFraction], but only between words, up to the caller's line cap.
 *    Wrapping beats shrinking further because a 48 dp touch target is a minimum height, so the
 *    control grows to hold a second line, while text below 85 percent of its 1.0 size gets hard to read.
 * 4. A word wider than its line even at [FloorFraction] keeps shrinking until it fits whole, down to
 *    [MinFraction]. A word is never split across lines.
 * 5. Only when a word is still too wide at [MinFraction] does the label draw on one line at
 *    [MinFraction] and end in an ellipsis. The full text stays in the semantics for screen readers.
 */
object FitTextLimits {
    val FloorFraction: Float = 0.85f
    val MinFraction: Float = 0.5f

    /** Each trial size is this fraction of the 1.0 size smaller than the last. */
    val StepFraction: Float = 0.02f

    /** Lines a fitted label may wrap to (between words only) once it reaches [FloorFraction]. */
    val LabelMaxLines: Int = 2
}

/** One trial layout at a candidate size. */
internal data class FitTrial(val lines: Int, val overflow: Boolean, val midWordBreak: Boolean) {
    val oneLine: Boolean get() = lines <= 1 && !overflow
    val wordSafe: Boolean get() = !overflow && !midWordBreak
}

/**
 * The chosen size in px. [fits] is false when even [FitTextLimits.MinFraction] cannot keep every
 * word whole, so a label draws one line at [px] with an ellipsis.
 */
internal data class FitChoice(val px: Float, val fits: Boolean)

/** Characters a line may end on without splitting a word: a dash, a slash or a zero width space. */
private const val BREAK_AFTER = "-/‐–—​"

/** Hyphens that join a compound name such as AQ-7500 or Wi-Fi into one word. */
private const val JOINING_HYPHENS = "-‐"

/**
 * True when a line of [text] ends inside a word. [lineEnds] are the exclusive end offsets of every
 * line but the last. A break is between words when whitespace sits on either side of it or the line
 * ends on a dash, slash or zero width space. A hyphen between two letters or digits (AQ-7500,
 * Wi-Fi) joins one word, so a line may not end on it.
 */
internal fun breaksInsideWord(text: CharSequence, lineEnds: List<Int>): Boolean = lineEnds.any { end ->
    end > 0 && end < text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() &&
        (text[end - 1] !in BREAK_AFTER || joinsOneWord(text, end))
}

private fun joinsOneWord(text: CharSequence, end: Int): Boolean =
    text[end - 1] in JOINING_HYPHENS && end >= 2 && text[end - 2].isLetterOrDigit() && text[end].isLetterOrDigit()

/** The end offset of every line but the last, the inputs of [breaksInsideWord]. */
internal fun TextLayoutResult.innerLineEnds(): List<Int> = (0 until lineCount - 1).map { getLineEnd(it) }

/** True when [layout] ends a line inside a word. */
fun breaksInsideWord(layout: TextLayoutResult): Boolean =
    breaksInsideWord(layout.layoutInput.text.text, layout.innerLineEnds())

/**
 * The largest size in [sizes] (descending) that passes [ok], or null. Fitting is monotonic in size,
 * so after a failed first try the boundary is found by binary search.
 */
private inline fun largestPassing(sizes: List<Float>, ok: (Float) -> Boolean): Float? {
    if (sizes.isEmpty()) return null
    if (ok(sizes.first())) return sizes.first()
    var lo = 1
    var hi = sizes.lastIndex
    var found: Float? = null
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (ok(sizes[mid])) {
            found = sizes[mid]
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return found
}

/** Sizes from [top] down to [bottom] in steps of [step], always ending on [bottom]. */
private fun descending(top: Float, bottom: Float, step: Float): List<Float> {
    if (top < bottom) return emptyList()
    val out = ArrayList<Float>()
    var s = top
    while (s > bottom + step / 2) {
        out += s
        s -= step
    }
    out += bottom
    return out
}

/**
 * Picks the rendered size in px for a fitted text, in the order of [FitTextLimits]. [preferredPx] is
 * the user's scaled size and [oneScalePx] the same style at font scale 1.0, both through the real
 * density. [singleLine] is a label, otherwise body text that keeps its size and only shrinks when a
 * single word is wider than the line.
 */
internal fun chooseFit(
    preferredPx: Float,
    oneScalePx: Float,
    singleLine: Boolean,
    trial: (Float) -> FitTrial,
): FitChoice {
    val one = minOf(oneScalePx, preferredPx)
    val floor = one * FitTextLimits.FloorFraction
    val min = one * FitTextLimits.MinFraction
    val step = maxOf(one * FitTextLimits.StepFraction, 0.5f)
    if (!singleLine) {
        return largestPassing(descending(preferredPx, min, step)) { trial(it).wordSafe }?.let { FitChoice(it, true) }
            ?: FitChoice(min, fits = false)
    }
    largestPassing(descending(preferredPx, one, step)) { trial(it).oneLine }?.let { return FitChoice(it, true) }
    largestPassing(descending(one, floor, step).drop(1)) { trial(it).oneLine }?.let { return FitChoice(it, true) }
    return largestPassing(descending(floor, min, step)) { trial(it).wordSafe }?.let { FitChoice(it, true) }
        ?: FitChoice(min, fits = false)
}

/**
 * Set during layout when a label cannot keep a word whole even at [FitTextLimits.MinFraction], so
 * [FitText] draws it on one line with an ellipsis. [atWidth] is the line width it was cut at. Only a
 * wider line can hold more, so only a wider line tries the caller's line cap again.
 */
@Stable
internal class OneLineCut {
    var active: Boolean by mutableStateOf(false)
    var atWidth: Int = 0
}

/**
 * A [TextAutoSize] that keeps every word whole. [base] is the style's size in sp for the current
 * scale and [oneScale] its size at font scale 1.0, read as dp through the layout's density.
 */
internal data class WordSafeAutoSize(
    val base: TextUnit,
    val oneScale: TextUnit,
    val singleLine: Boolean,
    val cut: OneLineCut? = null,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val size = if (base.isSp) base else DEFAULT_SIZE
        val one = if (oneScale.isSp) oneScale else size
        if (cut != null && cut.active && constraints.maxWidth > cut.atWidth) cut.active = false
        val choice = chooseFit(size.toPx(), one.value.dp.toPx(), singleLine) { candidate ->
            val layout = performLayout(constraints, text, candidate.toSp())
            FitTrial(layout.lineCount, layout.hasVisualOverflow, breaksInsideWord(text.text, layout.innerLineEnds()))
        }
        if (cut != null && !choice.fits && !cut.active) {
            cut.atWidth = constraints.maxWidth
            cut.active = true
        }
        return choice.px.toSp()
    }

    private companion object {
        val DEFAULT_SIZE = 14.sp
    }
}

/**
 * True when every one of [labels] fits on one line in [slotPx] at [FitTextLimits.FloorFraction] of
 * its 1.0 size [oneScale], so a row of equal slots (tabs) holds them without wrapping or splitting a word.
 */
internal fun TextMeasurer.allFitAtFloor(
    labels: List<String>,
    style: TextStyle,
    oneScale: TextUnit,
    slotPx: Float,
    density: Density,
): Boolean {
    if (slotPx <= 0f) return labels.isEmpty()
    val size = if (style.fontSize.isSp) style.fontSize else 14.sp
    val one = if (oneScale.isSp) oneScale else size
    val floorStyle = with(density) {
        val floorPx = minOf(size.toPx(), one.value.dp.toPx()) * FitTextLimits.FloorFraction
        style.wordSafe(heading = true).copy(fontSize = floorPx.toSp())
    }
    return labels.all { measure(it, floorStyle, maxLines = 1, softWrap = false, density = density).size.width <= slotPx }
}

/**
 * [style] set to break only between words: [LineBreak.Heading] for labels and titles,
 * [LineBreak.Paragraph] for sentences, never hyphenated. Line height and tracking move to em so
 * they follow the fitted size.
 */
fun TextStyle.wordSafe(heading: Boolean): TextStyle {
    val size = fontSize
    return copy(
        lineBreak = if (heading) LineBreak.Heading else LineBreak.Paragraph,
        hyphens = Hyphens.None,
        lineHeight = if (size.isSp && lineHeight.isSp) (lineHeight.value / size.value).em else lineHeight,
        letterSpacing = if (size.isSp && letterSpacing.isSp) (letterSpacing.value / size.value).em else letterSpacing,
    )
}

/**
 * A short label (a tab, chip, button, segment, card title, eyebrow, legend word or hero value) that
 * stays on one line and shrinks to fit, never below its own size at font scale 1.0 while that fits,
 * then to [FitTextLimits.FloorFraction] of it, then wraps between words up to [maxLines]. A word
 * wider than its line shrinks on until it fits whole, down to [FitTextLimits.MinFraction]. Only past
 * that does the label draw on one line with an ellipsis. A word is never split across lines, and
 * the full text stays in the semantics, so TalkBack and VoiceOver read all of it.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = FitTextLimits.LabelMaxLines,
) {
    val merged = LocalTextStyle.current.merge(style)
    val oneScale = MaterialTheme.typography.oneScaleSize(merged)
    val cut = remember(text, maxLines) { OneLineCut() }
    val autoSize = remember(merged.fontSize, oneScale, cut) { WordSafeAutoSize(merged.fontSize, oneScale, singleLine = true, cut = cut) }
    Text(
        text,
        modifier = modifier,
        color = color,
        autoSize = autoSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        maxLines = if (cut.active) 1 else maxLines,
        overflow = TextOverflow.Ellipsis,
        style = merged.wordSafe(heading = true),
    )
}

/**
 * Body text (a sentence, an info sheet paragraph) at the user's full size, wrapping only between
 * words. It shrinks only when a single word is wider than the line, down to
 * [FitTextLimits.MinFraction], so Arabic and long English words are never split. Pass [heading] =
 * true for a multi-line title.
 */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    heading: Boolean = false,
) {
    val merged = LocalTextStyle.current.merge(style)
    val oneScale = MaterialTheme.typography.oneScaleSize(merged)
    val autoSize = remember(merged.fontSize, oneScale) { WordSafeAutoSize(merged.fontSize, oneScale, singleLine = false) }
    Text(text, modifier = modifier, color = color, autoSize = autoSize, textAlign = textAlign, style = merged.wordSafe(heading = heading))
}

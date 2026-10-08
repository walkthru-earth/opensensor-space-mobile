package earth.walkthru.opensensor.designsystem

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypographyTest {
    @Test
    fun arabicScriptLanguagesAreDetectedFromCodesAndTags() {
        for (tag in listOf("ar", "ar-EG", "fa", "ur-PK", "ps", "ckb", "ckb_IQ", "sd", "ug", "AR")) {
            assertTrue(usesArabicScript(tag), tag)
        }
        for (tag in listOf("", "en", "en-US", "de", "he", "tr", "arn")) {
            assertFalse(usesArabicScript(tag), tag)
        }
    }

    @Test
    fun displayStylesUseTheNumberFaceWithTabularDigits() {
        val t = brandTypography(text = FontFamily.Serif, numbers = FontFamily.Monospace)
        for (style in listOf(t.displayLarge, t.displayMedium, t.displaySmall)) {
            assertEquals(FontFamily.Monospace, style.fontFamily)
            assertEquals(TABULAR, style.fontFeatureSettings)
        }
        for (style in listOf(t.headlineLarge, t.titleMedium, t.bodyMedium, t.labelSmall)) {
            assertEquals(FontFamily.Serif, style.fontFamily)
        }
    }

    @Test
    fun everyRequestedWeightIsInsideTheBundledFontAxis() {
        for ((file, weights) in listOf("quicksand.ttf" to QuicksandWeights, "cairo.ttf" to CairoWeights)) {
            val (min, max) = weightAxis(File("src/commonMain/composeResources/font/$file").readBytes())
            for (w in weights) assertTrue(w.toFloat() in min..max, "$file weight $w outside $min..$max")
        }
    }

    @Test
    fun licenseTextsShipWithTheFonts() {
        for (name in listOf("Quicksand-OFL.txt", "Cairo-OFL.txt")) {
            val text = File("src/commonMain/composeResources/files/licenses/$name").readText()
            assertTrue("SIL OPEN FONT LICENSE" in text.uppercase(), name)
        }
    }

    /** Reads the `wght` axis range from an OpenType `fvar` table. */
    private fun weightAxis(font: ByteArray): Pair<Float, Float> {
        val b = ByteBuffer.wrap(font)
        val tables = b.getShort(4).toInt()
        val fvar = (0 until tables).map { 12 + it * 16 }.first { String(font, it, 4) == "fvar" }
        val start = b.getInt(fvar + 8)
        val axesOffset = b.getShort(start + 4).toInt()
        val axisCount = b.getShort(start + 8).toInt()
        val axisSize = b.getShort(start + 10).toInt()
        fun fixed(at: Int) = b.getInt(at) / 65536f
        for (i in 0 until axisCount) {
            val axis = start + axesOffset + i * axisSize
            if (String(font, axis, 4) == "wght") return fixed(axis + 4) to fixed(axis + 12)
        }
        error("no wght axis")
    }
}

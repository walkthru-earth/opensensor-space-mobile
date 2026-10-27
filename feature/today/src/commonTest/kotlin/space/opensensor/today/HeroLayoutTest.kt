package space.opensensor.today

import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.BODY_TEXT_CONTRAST
import space.opensensor.designsystem.BrandDarkScheme
import space.opensensor.designsystem.BrandLightScheme
import space.opensensor.designsystem.contrastRatio
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroLayoutTest {
    @Test
    fun theQuietUnitAndTitleKeepBodyTextContrastOnTheHeroInBothThemes() {
        for ((name, scheme) in listOf("light" to BrandLightScheme, "dark" to BrandDarkScheme)) {
            val card = scheme.primaryContainer
            val ratio = contrastRatio(heroQuiet(scheme.onPrimaryContainer, card), card)
            assertTrue(ratio >= BODY_TEXT_CONTRAST, "$name hero quiet ink contrast $ratio")
        }
    }

    @Test
    fun largeTextOrANarrowCardStacksThePeacockAboveTheNumber() {
        assertFalse(heroStacked(360.dp, 1f))
        assertFalse(heroStacked(320.dp, 1.15f))
        assertTrue(heroStacked(320.dp, 1.3f))
        assertTrue(heroStacked(360.dp, 2f))
        assertTrue(heroStacked(280.dp, 1f))
    }
}

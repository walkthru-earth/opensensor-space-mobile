package space.opensensor.devices

import space.opensensor.designsystem.BrandDarkScheme
import space.opensensor.designsystem.BrandLightScheme
import space.opensensor.designsystem.UI_COMPONENT_CONTRAST
import space.opensensor.designsystem.contrastRatio
import kotlin.test.Test
import kotlin.test.assertTrue

/** The known card chevron is a control, so it keeps 3.0 contrast on the card in light and dark (#117). */
class ChevronContrastTest {
    @Test
    fun theKnownCardChevronReachesUiContrastInLightAndDark() {
        for ((name, scheme) in listOf("light" to BrandLightScheme, "dark" to BrandDarkScheme)) {
            val ratio = contrastRatio(knownChevronTint(scheme), knownCardColor(scheme))
            println("known card chevron $name ${(ratio * 100).toInt() / 100.0}")
            assertTrue(ratio >= UI_COMPONENT_CONTRAST, "$name chevron contrast $ratio < $UI_COMPONENT_CONTRAST")
        }
    }
}

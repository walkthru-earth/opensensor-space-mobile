package earth.walkthru.opensensor.designsystem

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentsTest {
    private val rounded = with(AppIcons) {
        listOf(
            ArrowBack, ArrowForward, BatteryChargingFull, BatteryStd, Bluetooth, BugReport, CheckCircle, ChevronLeft,
            ChevronRight, CloudOff, CloudSync, Download, Error, IosShare, Link, LinkOff, Lock, LockOpen, MoreVert, Radar,
            Refresh, RestartAlt, Schedule, Send, Settings, Stop, Storage, Sync, SyncLock, TableChart, Visibility,
            VisibilityOff, Warning, Wifi,
        )
    }
    private val outlined = with(AppIcons.Outlined) { listOf(Info, Map, Sensors, Settings) }

    @Test
    fun everyVendoredIconIsAMaterialSizedVectorWithFilledPaths() {
        val all = rounded + outlined
        assertEquals(38, all.size)
        assertEquals(all.size, all.map { it.name }.toSet().size, "icon names are unique")
        for (icon in all) {
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24f, icon.viewportWidth, icon.name)
            val paths = icon.root.toList().filterIsInstance<VectorPath>()
            assertTrue(paths.isNotEmpty(), icon.name)
            for (path in paths) {
                assertTrue(path.pathData.size > 2, "${icon.name} has path nodes")
                assertTrue(path.fill is SolidColor, icon.name)
            }
        }
    }

    @Test
    fun onlyDirectionalIconsMirrorInRightToLeft() {
        val mirrored = (rounded + outlined).filter(ImageVector::autoMirror).map { it.name }.toSet()
        assertEquals(
            setOf(
                "AutoMirrored.Rounded.ArrowBack",
                "AutoMirrored.Rounded.ArrowForward",
                "AutoMirrored.Rounded.Send",
                "Rounded.ChevronLeft",
                "Rounded.ChevronRight",
            ),
            mirrored,
        )
    }

    @Test
    fun contentCardsUseTheOne32DpRadius() {
        assertEquals(BrandShapes.extraLarge, CardShape)
        val corner = CardShape.topStart.toPx(Size(400f, 400f), Density(1f))
        assertEquals(32f, corner)
    }

    @Test
    fun signalBarsFollowTheRssiSteps() {
        assertEquals(4, signalBars(-50))
        assertEquals(4, signalBars(-55))
        assertEquals(3, signalBars(-56))
        assertEquals(3, signalBars(-67))
        assertEquals(2, signalBars(-68))
        assertEquals(2, signalBars(-80))
        assertEquals(1, signalBars(-81))
        assertEquals(1, signalBars(-120))
    }

    @Test
    fun touchTargetTokensMeet48DpMinimumWith8DpSpacing() {
        assertEquals(48.dp, MinTouchTarget)
        assertEquals(48.dp, FloatingControl.Size)
        assertEquals(8.dp, MinTouchSpacing)
        assertEquals(8.dp, FloatingControl.MinSpacing)
    }

    @Test
    fun displayTypographyClampsAboveMaxDisplayFontScaleWhileBodyScales() {
        assertEquals(1f, displayFontScaleFactor(1.0f), 0.0001f)
        assertEquals(1f, displayFontScaleFactor(1.35f), 0.0001f)
        assertEquals(1.35f / 2.0f, displayFontScaleFactor(2.0f), 0.0001f)

        val base = brandTypography(
            text = androidx.compose.ui.text.font.FontFamily.SansSerif,
            numbers = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontScale = 1.0f,
        )
        val scaled200 = brandTypography(
            text = androidx.compose.ui.text.font.FontFamily.SansSerif,
            numbers = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontScale = 2.0f,
        )
        // At 2.0x system font scale, the effective rendered displayLarge sp * 2.0 caps at 84 * 1.35 = 113.4.
        assertEquals(84f * TypographyLimits.MaxDisplayFontScale, scaled200.displayLarge.fontSize.value * 2.0f, 0.01f)
        // Body styles keep their uncompensated sp value so the OS 2.0x font scale doubles them in full.
        assertEquals(base.bodyMedium.fontSize.value, scaled200.bodyMedium.fontSize.value, 0.001f)
    }

    @Test
    fun reduceMotionHelpersSkipTransitionsAndCoarsenLiveTickers() {
        assertTrue(!shouldReduceMotion(reduceMotion = false, motionScaleFactor = 1f))
        assertTrue(shouldReduceMotion(reduceMotion = true, motionScaleFactor = 1f))
        assertTrue(shouldReduceMotion(reduceMotion = false, motionScaleFactor = 0f))
        assertEquals(420, reduceMotionDurationMillis(420, reduceMotion = false))
        assertEquals(0, reduceMotionDurationMillis(420, reduceMotion = true))
        assertEquals(1000L, coarsenTickPeriodMs(1000L, reduceMotion = false))
        assertEquals(10_000L, coarsenTickPeriodMs(1000L, reduceMotion = true))
    }

    @Test
    fun valueDescriptionJoinsLabelReadingAndStateWithLocalisedSeparator() {
        assertEquals(
            "PM2.5, 12 µg/m³, Fair",
            formatValueDescription(label = "PM2.5", value = "12", unit = "µg/m³", state = "Fair"),
        )
        assertEquals(
            "Battery, 80 %",
            formatValueDescription(label = "Battery", value = "80", unit = "%"),
        )
        assertEquals(
            "PM2.5، 12 µg/m³، مقبول",
            formatValueDescription(label = "PM2.5", value = "12", unit = "µg/m³", state = "مقبول", separator = "،"),
        )
    }
}

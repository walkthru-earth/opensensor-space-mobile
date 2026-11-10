package space.opensensor.designsystem

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    fun displayNumeralsFollowThePlatformScaleUpToTheCap() {
        // Density(density, fontScale) is the platform's own converter, the Android 14 tables on the
        // Android host and the linear scale on the iOS simulator, so no hand-made converter stands in
        // for a phone. On iOS 1.5x and up meets the 1.35x cap, the Android tables stay under it.
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            val density = Density(density = 2f, fontScale = scale)
            val type = brandTypography(FontFamily.SansSerif, FontFamily.Monospace, density)
            for ((style, base) in listOf(
                type.displayLarge to DisplaySizes.LARGE,
                type.displayMedium to DisplaySizes.MEDIUM,
                type.displaySmall to DisplaySizes.SMALL,
            )) {
                val rendered = with(density) { style.fontSize.toDp().value }
                val converted = with(density) { base.sp.toDp().value }
                val cap = base * TypographyLimits.MaxDisplayFontScale
                assertEquals(minOf(converted, cap), rendered, 0.05f, "display $base sp at $scale")
                if (scale >= 1f) assertTrue(rendered >= base - 0.05f, "display $base sp renders $rendered dp at $scale")
            }
            // Body styles keep their sp so the system scale applies in full.
            assertEquals(14f, type.bodyMedium.fontSize.value, 0.001f)
        }
    }

    @Test
    fun reduceMotionAndSpokenTickHelpersBehaveDeterministically() {
        assertTrue(!shouldReduceMotion(reduceMotion = false, motionScaleFactor = 1f))
        assertTrue(shouldReduceMotion(reduceMotion = true, motionScaleFactor = 1f))
        assertTrue(shouldReduceMotion(reduceMotion = false, motionScaleFactor = 0f))
        assertEquals(420, reduceMotionDurationMillis(420, reduceMotion = false))
        assertEquals(0, reduceMotionDurationMillis(420, reduceMotion = true))
        assertEquals(1_000L, quantiseSpokenMillis(nowMs = 1_000L, anchorMs = 1_000L))
        assertEquals(1_000L, quantiseSpokenMillis(nowMs = 9_999L, anchorMs = 1_000L))
        assertEquals(11_000L, quantiseSpokenMillis(nowMs = 11_000L, anchorMs = 1_000L))
        assertEquals(11_000L, quantiseSpokenMillis(nowMs = 19_500L, anchorMs = 1_000L))
        assertEquals(21_000L, quantiseSpokenMillis(nowMs = 21_000L, anchorMs = 1_000L))
    }

    @Test
    fun typographyLimitsAllowMultiLineWrapping() {
        assertEquals(2, TypographyLimits.EyebrowMaxLines)
        assertEquals(3, TypographyLimits.StatLabelMaxLines)
        assertEquals(2, TypographyLimits.StatValueMaxLines)
        assertEquals(2, TypographyLimits.TickingMaxLines)
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

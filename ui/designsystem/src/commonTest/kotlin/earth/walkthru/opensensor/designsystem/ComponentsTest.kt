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
    fun displayTypographyNeverShrinksBelowDefaultAndClampsRenderedDpAboveMaxScale() {
        val defaultDensity = Density(density = 2f, fontScale = 1.0f)
        val linear200Density = Density(density = 2f, fontScale = 2.0f)
        // Models Android 14 non-linear font scaling at 200 percent, where 84 sp maps to ~86 dp
        // instead of 168 dp while 14 sp body text doubles to 28 dp.
        val android14NonLinear200Density = object : Density {
            override val density: Float = 2f
            override val fontScale: Float = 2f
            override fun androidx.compose.ui.unit.TextUnit.toDp(): androidx.compose.ui.unit.Dp {
                val sp = value
                val dp = when {
                    sp >= 60f -> 84f + (sp - 84f) * 0.9f + 2f
                    sp >= 40f -> 60f + (sp - 48f) * 1.1f
                    sp >= 28f -> 46f + (sp - 32f) * 1.2f
                    else -> sp * 2f
                }
                return dp.dp
            }

            override fun androidx.compose.ui.unit.Dp.toSp(): androidx.compose.ui.unit.TextUnit {
                val d = value
                val sp = when {
                    d >= 75f -> 84f + (d - 86f) / 0.9f
                    d >= 52f -> 48f + (d - 60f) / 1.1f
                    d >= 38f -> 32f + (d - 46f) / 1.2f
                    else -> d / 2f
                }
                return androidx.compose.ui.unit.TextUnit(sp, androidx.compose.ui.unit.TextUnitType.Sp)
            }
        }

        val base = brandTypography(
            text = androidx.compose.ui.text.font.FontFamily.SansSerif,
            numbers = androidx.compose.ui.text.font.FontFamily.Monospace,
            density = defaultDensity,
        )
        val scaledLinear = brandTypography(
            text = androidx.compose.ui.text.font.FontFamily.SansSerif,
            numbers = androidx.compose.ui.text.font.FontFamily.Monospace,
            density = linear200Density,
        )
        val scaledNonLinear = brandTypography(
            text = androidx.compose.ui.text.font.FontFamily.SansSerif,
            numbers = androidx.compose.ui.text.font.FontFamily.Monospace,
            density = android14NonLinear200Density,
        )

        for ((baseStyle, linearStyle, nonLinearStyle) in listOf(
            Triple(base.displayLarge, scaledLinear.displayLarge, scaledNonLinear.displayLarge),
            Triple(base.displayMedium, scaledLinear.displayMedium, scaledNonLinear.displayMedium),
            Triple(base.displaySmall, scaledLinear.displaySmall, scaledNonLinear.displaySmall),
        )) {
            val baseDp = with(defaultDensity) { baseStyle.fontSize.toDp().value }
            val maxDp = baseDp * TypographyLimits.MaxDisplayFontScale
            val linearDp = with(linear200Density) { linearStyle.fontSize.toDp().value }
            val nonLinearDp = with(android14NonLinear200Density) { nonLinearStyle.fontSize.toDp().value }

            assertTrue(linearDp >= baseDp - 0.01f, "linear $linearDp >= $baseDp")
            assertTrue(linearDp <= maxDp + 0.05f, "linear $linearDp <= $maxDp")
            assertTrue(nonLinearDp >= baseDp - 0.01f, "nonLinear $nonLinearDp >= $baseDp")
            assertTrue(nonLinearDp <= maxDp + 0.05f, "nonLinear $nonLinearDp <= $maxDp")
        }

        // Under Android 14 non-linear 200 percent scaling, 84 sp renders at 86 dp (>= 84 dp).
        assertEquals(
            86f,
            with(android14NonLinear200Density) { scaledNonLinear.displayLarge.fontSize.toDp().value },
            0.05f,
        )
        // Body styles keep their uncompensated sp value so the OS 2.0x font scale doubles them in full.
        assertEquals(base.bodyMedium.fontSize.value, scaledLinear.bodyMedium.fontSize.value, 0.001f)
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

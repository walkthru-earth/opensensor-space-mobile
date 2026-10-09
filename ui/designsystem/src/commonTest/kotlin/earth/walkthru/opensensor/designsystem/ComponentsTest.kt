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
}

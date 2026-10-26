package space.opensensor.publicfeed

import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng as UberLatLng

/**
 * H3 through `com.uber:h3` (JNI), loaded on first use, never at app start.
 *
 * Android loads `libh3-java.so` from the APK's native libraries (the app copies the arm64 and arm
 * builds out of the jar, see `ExtractH3Jni` in `app/build.gradle.kts`). A desktop JVM, as in the
 * tests, extracts the host build from the jar instead. A device without a build (x86_64) gets
 * [unavailable], so Discover shows its empty state instead of crashing.
 */
class UberH3Grid internal constructor(private val core: H3Core) : H3Grid {
    override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = core.latLngToCell(lat, lng, res)

    override fun cellToLatLng(cell: Long): LatLng = core.cellToLatLng(cell).let { LatLng(it.lat, it.lng) }

    override fun cellToBoundary(cell: Long): List<LatLng> = core.cellToBoundary(cell).map { LatLng(it.lat, it.lng) }

    override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> =
        core.polygonToCells(ring.map { UberLatLng(it.lat, it.lng) }, emptyList(), res)

    companion object {
        val instance: H3Grid by lazy {
            val core = runCatching { H3Core.newSystemInstance() }
                .recoverCatching { H3Core.newInstance() }
                .getOrNull()
            core?.let { UberH3Grid(it) } ?: unavailable
        }

        private val unavailable = object : H3Grid {
            override val available: Boolean get() = false
            override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = 0L
            override fun cellToLatLng(cell: Long): LatLng = LatLng(0.0, 0.0)
            override fun cellToBoundary(cell: Long): List<LatLng> = emptyList()
            override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
        }
    }
}

actual fun platformH3Grid(): H3Grid = UberH3Grid.instance

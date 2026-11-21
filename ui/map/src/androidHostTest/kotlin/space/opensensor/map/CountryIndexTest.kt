package space.opensensor.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.LatLng
import java.io.File

class CountryIndexTest {
    private val index: CountryIndex by lazy {
        val file = File("src/commonMain/composeResources/files/places/world_countries_simple.parquet")
        assertTrue("bundled countries parquet missing at ${file.absolutePath}", file.isFile)
        CountryIndex.parse(file.readBytes())
    }

    @Test
    fun loadsBundledCountriesAndResolvesMajorCities() {
        assertTrue("expected >150 bundled countries, got ${index.size}", index.size > 150)
        assertEquals("EG", index.countryFor(30.0444, 31.2357)) // Cairo
        assertEquals("SA", index.countryFor(24.7136, 46.6753)) // Riyadh
        assertEquals("GH", index.countryFor(5.6037, -0.1870)) // Accra
        assertEquals("US", index.countryFor(40.7128, -74.0060)) // New York
        assertEquals("GB", index.countryFor(51.5074, -0.1278)) // London
        assertEquals("FR", index.countryFor(48.8566, 2.3522)) // Paris
        assertEquals("DE", index.countryFor(52.5200, 13.4050)) // Berlin
        assertNull(index.countryFor(0.0, 0.0)) // Gulf of Guinea ocean
    }

    @Test
    fun borderCellReturnsNullInsteadOfGuessing() {
        val borderGrid = object : H3Grid {
            override val available = true
            override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = 0x883e564a01fffffL
            override fun cellToLatLng(cell: Long): LatLng = LatLng(30.0444, 31.2357) // Egypt
            override fun cellToBoundary(cell: Long): List<LatLng> = listOf(
                LatLng(30.0444, 31.2357), // Egypt
                LatLng(24.7136, 46.6753), // Saudi Arabia
            )
            override fun gridDisk(cell: Long, k: Int): List<Long> = listOf(cell)
            override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
        }
        assertNull(index.countryForCell(borderGrid, "883e564a01fffff"))
    }
}

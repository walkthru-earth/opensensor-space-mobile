package earth.walkthru.aqsync.publicfeed

/** Placeholder until the H3 C binding lands. */
internal object UnavailableH3Grid : H3Grid {
    override val available: Boolean get() = false

    override fun latLngToCell(lat: Double, lng: Double, res: Int): Long = 0L

    override fun cellToLatLng(cell: Long): LatLng = LatLng(0.0, 0.0)

    override fun cellToBoundary(cell: Long): List<LatLng> = emptyList()

    override fun polygonToCells(ring: List<LatLng>, res: Int): List<Long> = emptyList()
}

actual fun platformH3Grid(): H3Grid = UnavailableH3Grid

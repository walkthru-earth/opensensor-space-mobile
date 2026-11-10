package space.opensensor.history

import space.opensensor.data.history.LocationSource
import space.opensensor.publicfeed.CellGeoJson
import space.opensensor.publicfeed.GeoBounds
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import space.opensensor.publicfeed.SequentialRamp

/**
 * What the History track map draws (#263). [geoJson] holds one Polygon per cell with the
 * properties `h3`, `color` and `last`, [bounds] is the area to fit, null when the cells straddle the
 * antimeridian, and [centre] is the newest cell's centre to open on. The outlines exist only on
 * screen. Nothing here is stored, logged or sent.
 */
internal data class TrackShape(val geoJson: String, val bounds: GeoBounds?, val centre: LatLng)

internal object HistoryTrackMap {
    /** Space around the cells when the map fits them, in degrees, about a cell's width at res 8. */
    const val PAD_DEG = 0.01

    /**
     * The shape of [track], each cell filled with [argbOf] its source. Null when [h3] draws no cell
     * outline for any of them.
     */
    fun shape(h3: H3Grid, track: HistoryTrack, argbOf: (LocationSource) -> Int): TrackShape? {
        val sb = StringBuilder(256)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var drawn = 0
        var west = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        for (c in track.cells) {
            val ring = CellGeoJson.ring(h3, c.cell)
            if (ring.isEmpty()) continue
            if (drawn > 0) sb.append(',')
            drawn++
            sb.append("{\"type\":\"Feature\",\"properties\":{\"h3\":\"").append(H3Index.toHex(c.cell)).append('"')
            sb.append(",\"color\":\"").append(SequentialRamp.hex(argbOf(c.source))).append('"')
            sb.append(",\"last\":").append(c.cell == track.last)
            sb.append("},\"geometry\":")
            CellGeoJson.appendPolygon(sb, ring)
            sb.append('}')
            for (p in ring) {
                west = minOf(west, p.lng)
                east = maxOf(east, p.lng)
                south = minOf(south, p.lat)
                north = maxOf(north, p.lat)
            }
        }
        sb.append("]}")
        if (drawn == 0) return null
        val newest = track.last ?: track.cells.last().cell
        val centre = h3.cellToLatLng(newest)
        // A ring kept whole across the antimeridian runs past 180, and cells on both sides span more
        // than half the world. The map then opens on the newest cell instead of fitting them.
        val wraps = east - west > 180.0 || east > 180.0 || west < -180.0
        val bounds = if (wraps) {
            null
        } else {
            GeoBounds(
                west = (west - PAD_DEG).coerceAtLeast(-180.0),
                south = (south - PAD_DEG).coerceAtLeast(-85.0),
                east = (east + PAD_DEG).coerceAtMost(180.0),
                north = (north + PAD_DEG).coerceAtMost(85.0),
            )
        }
        return TrackShape(sb.toString(), bounds, centre)
    }
}

package earth.walkthru.aqsync.data.history.parquet

import com.github.luben.zstd.Zstd

/**
 * The codec registry the app hands to [ParquetFile.open] for files that may come from *other* writers
 * (GeoParquet from PyArrow, GDAL, DuckDB): the pure module's built-ins (UNCOMPRESSED, LZ4_RAW, SNAPPY,
 * GZIP) plus ZSTD through `zstd-jni`, the one native library in the APK. Device and derived files never
 * need it — they are LZ4_RAW — so the archive readers keep using [ParquetCodecs.builtIn].
 *
 * Kept in `:app` on purpose: `:core:parquet` stays free of native dependencies and stays the spec a
 * Swift port follows (Apple ships libzstd for the same seam).
 */
object AppParquetCodecs {
    val all: ParquetCodecs by lazy {
        ParquetCodecs.builtIn.with(ParquetCodec.ZSTD) { input, size ->
            val src = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            Zstd.decompress(src, size)
        }
    }
}

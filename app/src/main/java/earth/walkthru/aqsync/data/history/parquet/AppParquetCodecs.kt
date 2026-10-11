package earth.walkthru.aqsync.data.history.parquet

import com.github.luben.zstd.ZstdDecompressCtx

/**
 * The codec registry the app hands to [ParquetFile.open] for files that may come from *other* writers
 * (GeoParquet from PyArrow, GDAL, DuckDB): the pure module's built-ins (UNCOMPRESSED, LZ4_RAW, SNAPPY,
 * GZIP) plus ZSTD through `zstd-jni`, the one native library in the APK. Device and derived files never
 * need it — they are LZ4_RAW — so the archive readers keep using [ParquetCodecs.builtIn].
 *
 * Kept in `:app` on purpose: `:core:parquet` is Kotlin Multiplatform and stays free of native
 * dependencies on both platforms. iOS has no ZSTD in the system libraries, so the public feed avoids it
 * (docs/devices-and-discovery.md).
 */
object AppParquetCodecs {
    /** One native ZSTD context per thread, reused for every page instead of one per call. */
    private val zstd = ThreadLocal.withInitial { ZstdDecompressCtx() }

    val all: ParquetCodecs by lazy {
        ParquetCodecs.builtIn.with(ParquetCodec.ZSTD) { src, srcOffset, srcLength, dst, dstOffset, dstLength ->
            zstd.get().decompressByteArray(dst, dstOffset, dstLength, src, srcOffset, srcLength)
        }
    }
}

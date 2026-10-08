package earth.walkthru.aqsync.data.history.parquet

import com.github.luben.zstd.Zstd
import java.io.File

private object Fixtures

actual fun fixtureBytes(path: String): ByteArray =
    checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

/** What `:app` does with zstd-jni's AAR, here with the JVM jar. */
actual val testCodecs: ParquetCodecs = ParquetCodecs.builtIn.with(ParquetCodec.ZSTD) { input, size -> Zstd.decompress(input, size) }

actual fun saveSample(dir: String, name: String, bytes: ByteArray) {
    File("build/$dir").apply { mkdirs() }.resolve(name).writeBytes(bytes)
}

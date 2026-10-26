package space.opensensor.data.history.parquet

import com.github.luben.zstd.ZstdDecompressCtx
import java.io.File

private object Fixtures

actual fun fixtureBytes(path: String): ByteArray =
    checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

private val zstd = ThreadLocal.withInitial { ZstdDecompressCtx() }

/** What `:app` does with zstd-jni's AAR (AppParquetCodecs), here with the JVM jar. */
actual val testCodecs: ParquetCodecs = ParquetCodecs.builtIn.with(ParquetCodec.ZSTD) { src, srcOffset, srcLength, dst, dstOffset, dstLength ->
    zstd.get().decompressByteArray(dst, dstOffset, dstLength, src, srcOffset, srcLength)
}

actual fun saveSample(dir: String, name: String, bytes: ByteArray) {
    File("build/$dir").apply { mkdirs() }.resolve(name).writeBytes(bytes)
}

actual fun tempLocalFile(name: String, bytes: ByteArray): LocalFile =
    File.createTempFile(name, ".bin").apply { deleteOnExit() }.also { it.writeBytes(bytes) }

actual fun writeLocalFile(file: LocalFile, bytes: ByteArray) = file.writeBytes(bytes)

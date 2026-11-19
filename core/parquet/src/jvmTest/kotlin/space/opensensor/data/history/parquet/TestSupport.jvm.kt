package space.opensensor.data.history.parquet

import com.github.luben.zstd.ZstdDecompressCtx
import java.io.File

private object Fixtures

actual fun fixtureBytes(path: String): ByteArray =
    checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

private val zstd = ThreadLocal.withInitial { ZstdDecompressCtx() }

/** Built-ins plus ZSTD through the zstd-jni JVM jar, so the tests decode every PyArrow fixture. */
actual val testCodecs: ParquetCodecs = ParquetCodecs.builtIn.with(ParquetCodec.ZSTD) { src, srcOffset, srcLength, dst, dstOffset, dstLength ->
    zstd.get().decompressByteArray(dst, dstOffset, dstLength, src, srcOffset, srcLength)
}

actual fun saveSample(dir: String, name: String, bytes: ByteArray) {
    File("build/$dir").apply { mkdirs() }.resolve(name).writeBytes(bytes)
}

actual fun tempLocalFile(name: String, bytes: ByteArray): LocalFile =
    File.createTempFile(name, ".bin").apply { deleteOnExit() }.also { it.writeBytes(bytes) }

actual fun writeLocalFile(file: LocalFile, bytes: ByteArray) = file.writeBytes(bytes)

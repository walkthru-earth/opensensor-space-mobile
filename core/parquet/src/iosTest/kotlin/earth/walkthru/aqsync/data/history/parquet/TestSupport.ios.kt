@file:OptIn(ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history.parquet

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * The checkout's fixture directory. `build.gradle.kts` passes it to the simulator process as
 * `SIMCTL_CHILD_PARQUET_FIXTURES`; simctl strips the prefix.
 */
internal val fixtureRoot: String
    get() = NSProcessInfo.processInfo.environment["PARQUET_FIXTURES"] as? String
        ?: error("PARQUET_FIXTURES is not set: run through ./gradlew :core:parquet:iosSimulatorArm64Test")

actual fun fixtureBytes(path: String): ByteArray {
    val reader = MappedDataReader("$fixtureRoot/$path")
    return reader.read(0, reader.size.toInt())
}

/** iOS has no ZSTD (Apple's Compression framework lacks it; docs/parquet.md): ZSTD fixtures are skipped. */
actual val testCodecs: ParquetCodecs = ParquetCodecs.builtIn

actual fun saveSample(dir: String, name: String, bytes: ByteArray) = Unit

/** The simulator's temporary directory, under a unique name so reruns never collide. */
actual fun tempLocalFile(name: String, bytes: ByteArray): LocalFile =
    LocalFile(NSTemporaryDirectory().trimEnd('/') + "/$name-${NSUUID().UUIDString}.bin").also { writeLocalFile(it, bytes) }

actual fun writeLocalFile(file: LocalFile, bytes: ByteArray) {
    val handle = fopen(file.path, "wb") ?: error("cannot create ${file.path}")
    try {
        if (bytes.isNotEmpty()) {
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), handle) }
            check(written.toInt() == bytes.size) { "short write to ${file.path}" }
        }
    } finally {
        fclose(handle)
    }
}

package earth.walkthru.aqsync.data.history.parquet

import platform.Foundation.NSProcessInfo

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

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.MappedDataReader
import platform.Foundation.NSProcessInfo

/**
 * The :core:parquet fixture directory of this checkout. `build.gradle.kts` passes it to the simulator
 * process as `SIMCTL_CHILD_PARQUET_FIXTURES`; simctl strips the prefix.
 */
private val fixtureRoot: String
    get() = NSProcessInfo.processInfo.environment["PARQUET_FIXTURES"] as? String
        ?: error("PARQUET_FIXTURES is not set: run through ./gradlew :core:history:iosSimulatorArm64Test")

actual fun fixtureBytes(path: String): ByteArray {
    val reader = MappedDataReader("$fixtureRoot/$path")
    return reader.read(0, reader.size.toInt())
}

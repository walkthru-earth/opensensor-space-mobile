package space.opensensor.family.air

import platform.Foundation.NSProcessInfo
import space.opensensor.data.history.parquet.LocalFileReader

/**
 * The :core:parquet fixture directory of this checkout. `build.gradle.kts` passes it to the simulator
 * process as `SIMCTL_CHILD_PARQUET_FIXTURES`, and simctl strips the prefix.
 */
private val fixtureRoot: String
    get() = NSProcessInfo.processInfo.environment["PARQUET_FIXTURES"] as? String
        ?: error("PARQUET_FIXTURES is not set, run through ./gradlew :family:air:iosSimulatorArm64Test")

actual fun fixtureBytes(path: String): ByteArray = LocalFileReader("$fixtureRoot/$path").use { reader -> reader.read(0, reader.size.toInt()) }

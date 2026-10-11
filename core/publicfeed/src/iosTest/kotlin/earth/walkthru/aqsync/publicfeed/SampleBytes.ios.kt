package earth.walkthru.aqsync.publicfeed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** `build.gradle.kts` passes the sample root as `SIMCTL_CHILD_DISCOVER_SAMPLE_ROOT`. simctl strips the prefix. */
actual fun sampleBytes(path: String): ByteArray = bytesUnder("DISCOVER_SAMPLE_ROOT", path)

/** `build.gradle.kts` passes the fixture root as `SIMCTL_CHILD_PUBLICFEED_FIXTURE_ROOT`. */
actual fun fixtureBytes(path: String): ByteArray = bytesUnder("PUBLICFEED_FIXTURE_ROOT", path)

@OptIn(ExperimentalForeignApi::class)
private fun bytesUnder(variable: String, path: String): ByteArray {
    val root = NSProcessInfo.processInfo.environment[variable] as? String
        ?: error("$variable is not set: run through ./gradlew :core:publicfeed:iosSimulatorArm64Test")
    val data = NSData.dataWithContentsOfFile("$root/$path") ?: error("no file $path under $variable")
    val out = ByteArray(data.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

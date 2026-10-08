package earth.walkthru.aqsync.publicfeed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** `build.gradle.kts` passes the sample root as `SIMCTL_CHILD_DISCOVER_SAMPLE_ROOT`. simctl strips the prefix. */
@OptIn(ExperimentalForeignApi::class)
actual fun sampleBytes(path: String): ByteArray {
    val root = NSProcessInfo.processInfo.environment["DISCOVER_SAMPLE_ROOT"] as? String
        ?: error("DISCOVER_SAMPLE_ROOT is not set: run through ./gradlew :core:publicfeed:iosSimulatorArm64Test")
    val data = NSData.dataWithContentsOfFile("$root/$path") ?: error("no sample file $path")
    val out = ByteArray(data.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

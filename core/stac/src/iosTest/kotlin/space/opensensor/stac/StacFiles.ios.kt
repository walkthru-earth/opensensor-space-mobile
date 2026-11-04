package space.opensensor.stac

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** `build.gradle.kts` passes the fixture root as `SIMCTL_CHILD_STAC_FIXTURE_ROOT`. simctl strips the prefix. */
@OptIn(ExperimentalForeignApi::class)
actual fun stacFile(path: String): ByteArray {
    val root = NSProcessInfo.processInfo.environment["STAC_FIXTURE_ROOT"] as? String
        ?: error("STAC_FIXTURE_ROOT is not set: run through ./gradlew :core:stac:iosSimulatorArm64Test")
    val data = NSData.dataWithContentsOfFile("$root/$path") ?: error("no file $path under STAC_FIXTURE_ROOT")
    val out = ByteArray(data.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinSharedNativeCompilation
import org.jetbrains.kotlin.konan.target.HostManager

/**
 * The root gates. `verify` is the host gate (Android lint and APK plus every host test) and
 * `verifyIos` is the iOS gate (simulator tests plus the framework link). The root build script
 * registers both tasks. Each convention plugin adds its own module tasks to them, so a module is
 * gated as soon as it applies a convention and no hand kept list can miss one.
 */
internal object Gates {
    const val HOST: String = "verify"
    const val IOS: String = "verifyIos"
}

/** Makes the root task [gate] depend on [taskNames] of this project. */
internal fun Project.addToGate(gate: String, vararg taskNames: String) {
    val paths = taskNames.map { "$path:$it" }
    rootProject.tasks.named { it == gate }.configureEach { dependsOn(paths) }
}

/**
 * Adds the metadata compile of every shared source set (`commonMain`, `iosMain` and the others) to
 * the gates. A platform compile builds the shared sources together with its actuals, so only the
 * metadata compile checks a shared source set on its own, for example an expect class that misses an
 * inherited member or a cinterop binding an intermediate source set cannot see. `assemble` and
 * `build` run these compiles (GitHub CodeQL autobuild runs `assemble`), so the gates do too. A
 * source set that only the iOS targets share compiles on a Mac only and joins `verifyIos`, the rest
 * join `verify`. The legacy `main` metadata compilation has no sources and is skipped.
 *
 * A `commonMain` metadata compile also builds the metadata jar of each module it depends on, and that
 * jar holds the iOS source sets too. When [iosBuildsEnabled] is false those iOS only compiles are
 * turned off, so `verify` needs no Xcode. They are `verifyIos` checks, which does not run then.
 */
internal fun Project.gateMetadataCompiles() {
    val iosBuilds = iosBuildsEnabled
    extensions.configure<KotlinMultiplatformExtension> {
        targets.withType(KotlinMetadataTarget::class.java).configureEach {
            compilations.configureEach {
                if (name == KotlinCompilation.MAIN_COMPILATION_NAME) return@configureEach
                val iosOnly = this is KotlinSharedNativeCompilation
                addToGate(if (iosOnly) Gates.IOS else Gates.HOST, compileKotlinTaskName)
                if (iosOnly && !iosBuilds) compileTaskProvider.configure { enabled = false }
            }
        }
    }
}

/**
 * Whether this build compiles for iOS. It needs a Mac with Xcode (the Command Line Tools ship no iOS
 * SDK) and no `opensensor.skipIos=true`, the setting CONTRIBUTING.md gives a Mac without Xcode. The
 * root build script reads it to decide whether `verify` runs `verifyIos`, and module build scripts
 * read it to leave out iOS only work, such as the H3 C library build of `:core:publicfeed`. Checked
 * once per build. A Mac without an iOS SDK and without the setting gets a lifecycle notice.
 */
val Project.iosBuildsEnabled: Boolean
    get() {
        val extra = rootProject.extensions.extraProperties
        if (!extra.has(IOS_BUILDS_KEY)) extra.set(IOS_BUILDS_KEY, detectIosBuilds())
        return extra.get(IOS_BUILDS_KEY) as Boolean
    }

private const val IOS_BUILDS_KEY = "opensensor.iosBuildsEnabled"

private fun Project.detectIosBuilds(): Boolean {
    if (!HostManager.hostIsMac) return false
    if (providers.gradleProperty("opensensor.skipIos").orNull == "true") return false
    // xcrun exits non zero when the active developer directory has no iOS SDK.
    val hasSdk = providers.exec {
        commandLine("xcrun", "--sdk", "iphoneos", "--show-sdk-path")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0
    if (!hasSdk) {
        logger.lifecycle(
            "No iOS SDK found (xcrun --sdk iphoneos), so this build skips verifyIos and the iOS only " +
                "builds. Install Xcode and select it with xcode-select to build for iOS, or set " +
                "opensensor.skipIos=true to skip iOS without this notice.",
        )
    }
    return hasSdk
}

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinSharedNativeCompilation

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
 */
internal fun Project.gateMetadataCompiles() {
    extensions.configure<KotlinMultiplatformExtension> {
        targets.withType(KotlinMetadataTarget::class.java).configureEach {
            compilations.configureEach {
                if (name == KotlinCompilation.MAIN_COMPILATION_NAME) return@configureEach
                val gate = if (this is KotlinSharedNativeCompilation) Gates.IOS else Gates.HOST
                addToGate(gate, compileKotlinTaskName)
            }
        }
    }
}

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Settings every shared module agrees on.
 *
 * The JVM bytecode target is 17 (Android's floor for this app). It is set as a compiler target, not
 * with `jvmToolchain(17)`, because the bundled Android Studio JBR and CI provide JDK 21 only and a
 * toolchain would need auto-provisioning (a network download) on every fresh machine.
 */
internal object OpenSensorConventions {
    val jvmTarget: JvmTarget = JvmTarget.JVM_17
    const val JAVA_RELEASE: Int = 17

    /** Android SDK levels of every shared Android library target. `:app` uses the same numbers. */
    const val COMPILE_SDK: Int = 37
    const val MIN_SDK: Int = 31

    /** Namespace root of the shared Compose modules. The module name is appended. */
    const val COMPOSE_NAMESPACE_ROOT: String = "space.opensensor"

    /** Name of the static framework the Xcode project links. Changing it breaks `ios/`. */
    const val IOS_FRAMEWORK_NAME: String = "OpenSensorShared"
}

internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).get()

/** Shared compiler options. Kept to settings that do not change generated code. */
internal fun KotlinCommonCompilerOptions.openSensorDefaults() {
    // Expect/actual classes are stable in practice but still flagged as Beta by the compiler.
    freeCompilerArgs.add("-Xexpect-actual-classes")
}

/** The two iOS targets every shared module builds: devices and Apple Silicon simulators. */
internal fun KotlinMultiplatformExtension.openSensorIosTargets() {
    iosArm64()
    iosSimulatorArm64()
}

/** Registers a `test` task that runs [hostTestTask], so every module keeps one host test task name. */
internal fun Project.registerTestAlias(hostTestTask: String) {
    if (tasks.names.contains("test")) return
    tasks.register("test") {
        group = "verification"
        description = "Runs the host tests (alias of $hostTestTask)."
        dependsOn(hostTestTask)
    }
}

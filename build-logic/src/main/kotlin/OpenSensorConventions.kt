import com.android.build.api.dsl.CompileSdkSpec
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Settings every shared module agrees on.
 *
 * The JVM bytecode target is 17 (Android's floor for this app). It is set as a compiler target, not
 * with `jvmToolchain(17)`, so the build needs one JDK only. Gradle runs on JDK 25, pinned in
 * `gradle/gradle-daemon-jvm.properties` for Android Studio, the terminal and CI, and compiles 17
 * bytecode from there. A JDK 17 toolchain would need a second JDK on every machine, or
 * auto-provisioning (a network download).
 */
internal object OpenSensorConventions {
    val jvmTarget: JvmTarget = JvmTarget.JVM_17
    const val JAVA_RELEASE: Int = 17

    /**
     * Android SDK levels of every shared Android library target and of `:app`. The compile SDK is
     * 37.2, the Android 17 QPR2 minor SDK, set through [openSensorCompileSdk].
     */
    const val COMPILE_SDK: Int = 37
    const val COMPILE_SDK_MINOR: Int = 2
    const val MIN_SDK: Int = 31

    /** Android target SDK of `:app`. Libraries have none. */
    const val TARGET_SDK: Int = 37

    /** Namespace root of the shared Compose modules. The module name is appended. */
    const val COMPOSE_NAMESPACE_ROOT: String = "space.opensensor"

    /** Name of the static framework the Xcode project links. Changing it breaks `ios/`. */
    const val IOS_FRAMEWORK_NAME: String = "OpenSensorShared"
}

/** Sets the compile SDK to [OpenSensorConventions.COMPILE_SDK].[OpenSensorConventions.COMPILE_SDK_MINOR]. */
internal fun CompileSdkSpec.openSensorCompileSdk() {
    version = release(OpenSensorConventions.COMPILE_SDK) {
        minorApiLevel = OpenSensorConventions.COMPILE_SDK_MINOR
    }
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

/**
 * Holds the iOS builds of every shared module to the library versions the shipped `:ios:shared`
 * framework resolves. A module that names fewer libraries otherwise resolves older transitive
 * versions, for example lifecycle 2.9.6 through Compose where the framework has 2.11.0. Kotlin/Native
 * keys each library cache by the whole set of libraries it links against, so those splits compiled
 * about five copies of every Compose cache in one iOS build, and each copy again on a cold CI cache
 * (docs/build-and-release.md, CI caches). A constraint adds no dependency. It only takes effect where
 * the library is already in the graph. Android and the JVM are left alone.
 */
internal fun Project.alignIosLibraryVersions() {
    val aligned = listOf(
        "kotlinx-atomicfu",
        "kotlinx-serialization-core",
        "kotlinx-datetime",
        "jetbrains-lifecycle-runtime-compose",
        "jetbrains-savedstate-compose",
    ).map(::catalogLibrary)
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.matching { it.name == "iosMain" }.configureEach {
            aligned.forEach { dependencies.constraints.add(implementationConfigurationName, it) }
        }
    }
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

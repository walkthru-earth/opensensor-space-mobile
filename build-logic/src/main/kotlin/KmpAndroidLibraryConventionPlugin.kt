import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `opensensor.kmp.android-library`. A Kotlin Multiplatform module compiled for Android and iOS, for a
 * library that ships separate Android and iOS artifacts (Room). No Compose and no JVM target.
 *
 * - Targets the AGP Kotlin Multiplatform Android library, `iosArm64` and `iosSimulatorArm64`.
 * - Namespace `space.opensensor.<module name>`, SDK levels from [OpenSensorConventions],
 *   bytecode 17.
 * - `kotlin-test` in `commonTest`. AGP compiles `commonTest` into the Android host test, so shared
 *   tests run on the host in `verify` and on the simulator in `verifyIos`.
 * - A `test` task that runs `testAndroidHostTest`.
 */
class KmpAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "${OpenSensorConventions.COMPOSE_NAMESPACE_ROOT}.${project.name}"
                compileSdk = OpenSensorConventions.COMPILE_SDK
                minSdk = OpenSensorConventions.MIN_SDK
                // Host tests run against the android.jar stubs. Room asks a stub Context for nothing it needs.
                withHostTest { isReturnDefaultValues = true }
                compilerOptions { jvmTarget.set(OpenSensorConventions.jvmTarget) }
            }
            openSensorIosTargets()
            compilerOptions { openSensorDefaults() }

            sourceSets.getByName("commonTest").dependencies {
                implementation(catalogLibrary("kotlin-test"))
            }
            sourceSets.getByName("androidHostTest").dependencies {
                implementation(catalogLibrary("junit"))
                implementation(catalogLibrary("kotlin-test"))
            }
        }

        registerTestAlias("testAndroidHostTest")
        addToGate(Gates.HOST, "testAndroidHostTest")
        addToGate(Gates.IOS, "iosSimulatorArm64Test")
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `opensensor.kmp.library`. A Kotlin Multiplatform module compiled for Android's JVM and for iOS.
 *
 * - Targets `jvm` (bytecode 17), `iosArm64` and `iosSimulatorArm64`.
 * - Default hierarchy template, so `commonMain`, `iosMain` and `jvmMain` exist without extra wiring.
 * - `kotlin-test` in `commonTest`. The JVM side resolves it to the JUnit 4 runner.
 * - A `test` task that runs `jvmTest`, so hooks and CI keep one task name per module.
 * - Gates. `jvmTest` joins `verify` and `iosSimulatorArm64Test` joins `verifyIos`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            jvm {
                compilerOptions { jvmTarget.set(OpenSensorConventions.jvmTarget) }
            }
            openSensorIosTargets()
            compilerOptions { openSensorDefaults() }

            sourceSets.getByName("commonTest").dependencies {
                implementation(catalogLibrary("kotlin-test"))
            }
        }

        registerTestAlias("jvmTest")
        addToGate(Gates.HOST, "jvmTest")
        addToGate(Gates.IOS, "iosSimulatorArm64Test")
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * `opensensor.kmp.ios-framework`. An iOS only Kotlin Multiplatform module that builds the static
 * `OpenSensorShared` framework for every iOS target, which the Xcode project links directly.
 *
 * There is no JVM target, so `commonMain` can depend on the Android and iOS Compose feature modules.
 * `commonTest` runs on the iOS simulator. Both the tests and the debug simulator framework link join
 * `verifyIos`.
 */
class IosFrameworkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            openSensorIosTargets()
            compilerOptions { openSensorDefaults() }

            sourceSets.getByName("commonTest").dependencies {
                implementation(catalogLibrary("kotlin-test"))
            }
            targets.withType(KotlinNativeTarget::class.java).configureEach {
                binaries.framework {
                    baseName = OpenSensorConventions.IOS_FRAMEWORK_NAME
                    isStatic = true
                    // Same as -Xbinary=bundleId=, so the link does not guess one from the base name.
                    binaryOption("bundleId", "earth.walkthru.opensensor.shared")
                }
            }
        }

        addToGate(Gates.IOS, "iosSimulatorArm64Test", "linkDebugFrameworkIosSimulatorArm64")
    }
}

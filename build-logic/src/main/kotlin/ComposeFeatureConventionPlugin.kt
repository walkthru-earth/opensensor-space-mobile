import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `opensensor.kmp.compose-feature`. A shared Compose Multiplatform UI module for Android and iOS.
 *
 * - Targets the AGP Kotlin Multiplatform Android library (not `jvm`, because maplibre-compose and
 *   other Compose libraries ship Android artifacts), `iosArm64` and `iosSimulatorArm64`.
 * - Namespace `space.opensensor.<module name>`, SDK levels from [OpenSensorConventions],
 *   Android resources on, bytecode 17.
 * - Compose runtime, foundation, Material 3, UI and resources in `commonMain`.
 * - Compose resources in the internal `<namespace>.resources` package, always generated.
 * - `kotlin-test` in `commonTest`. AGP compiles `commonTest` into the Android host test, so shared
 *   tests run on the host in `verify` and on the simulator in `verifyIos`.
 * - A `test` task that runs `testAndroidHostTest`.
 */
class ComposeFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val moduleNamespace = "${OpenSensorConventions.COMPOSE_NAMESPACE_ROOT}.${project.name}"

        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = moduleNamespace
                compileSdk = OpenSensorConventions.COMPILE_SDK
                minSdk = OpenSensorConventions.MIN_SDK
                androidResources { enable = true }
                // Merged Android resources, so a Robolectric host test can render Compose in a real window.
                withHostTest { isIncludeAndroidResources = true }
                compilerOptions { jvmTarget.set(OpenSensorConventions.jvmTarget) }
            }
            openSensorIosTargets()
            compilerOptions { openSensorDefaults() }

            sourceSets.getByName("commonMain").dependencies {
                implementation(catalogLibrary("compose-runtime"))
                implementation(catalogLibrary("compose-foundation"))
                implementation(catalogLibrary("compose-material3"))
                implementation(catalogLibrary("compose-ui"))
                implementation(catalogLibrary("compose-components-resources"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(catalogLibrary("kotlin-test"))
            }
            sourceSets.getByName("androidHostTest").dependencies {
                implementation(catalogLibrary("junit"))
                implementation(catalogLibrary("kotlin-test"))
            }
        }

        extensions.getByType<ComposeExtension>().extensions.configure<ResourcesExtension> {
            publicResClass = false
            packageOfResClass = "$moduleNamespace.resources"
            generateResClass = ResourcesExtension.ResourceClassGeneration.Always
        }

        registerTestAlias("testAndroidHostTest")
        addToGate(Gates.HOST, "testAndroidHostTest")
        addToGate(Gates.IOS, "iosSimulatorArm64Test")
    }
}

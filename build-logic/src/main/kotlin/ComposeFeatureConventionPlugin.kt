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
 * - Compose runtime, foundation, Material 3, UI and resources in `commonMain` ([applyOpenSensorCompose]),
 *   plus kotlinx-coroutines-core, which every screen controller uses.
 * - Compose resources in the internal `<namespace>.resources` package, always generated.
 * - `kotlin-test` and kotlinx-coroutines-test in `commonTest`. AGP compiles `commonTest` into the
 *   Android host test, so shared tests run on the host in `verify` and on the simulator in `verifyIos`.
 * - Compose UI test in `iosTest`, for the semantics tests that render headless on the simulator.
 * - A `test` task that runs `testAndroidHostTest`.
 */
class ComposeFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        applyOpenSensorCompose()

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
                implementation(catalogLibrary("kotlinx-coroutines-core"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(catalogLibrary("kotlin-test"))
                implementation(catalogLibrary("kotlinx-coroutines-test"))
            }
            sourceSets.named { it == "iosTest" }.configureEach {
                dependencies { implementation(catalogLibrary("compose-ui-test")) }
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
        gateMetadataCompiles()
    }
}

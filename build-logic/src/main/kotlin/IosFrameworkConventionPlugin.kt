import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * `opensensor.kmp.ios-framework`. The KMP library conventions plus the static
 * `OpenSensorShared` framework for every iOS target, which the Xcode project links directly.
 */
class IosFrameworkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KmpLibraryConventionPlugin::class.java)

        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType(KotlinNativeTarget::class.java).configureEach {
                binaries.framework {
                    baseName = OpenSensorConventions.IOS_FRAMEWORK_NAME
                    isStatic = true
                }
            }
        }
    }
}

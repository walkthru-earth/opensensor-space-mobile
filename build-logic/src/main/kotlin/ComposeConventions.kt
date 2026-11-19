import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Applies Compose Multiplatform and its compiler plugin to a Kotlin Multiplatform module and adds the
 * Compose runtime, foundation, Material 3, UI and resources to `commonMain`. The Compose feature
 * modules and the iOS framework share it, so both build their UI with the same Compose setup.
 */
internal fun Project.applyOpenSensorCompose() {
    pluginManager.apply("org.jetbrains.compose")
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.getByName("commonMain").dependencies {
            implementation(catalogLibrary("compose-runtime"))
            implementation(catalogLibrary("compose-foundation"))
            implementation(catalogLibrary("compose-material3"))
            implementation(catalogLibrary("compose-ui"))
            implementation(catalogLibrary("compose-components-resources"))
        }
    }
}

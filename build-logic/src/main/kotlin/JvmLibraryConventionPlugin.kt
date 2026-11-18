import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `opensensor.jvm.library`. A plain Kotlin/JVM module (bytecode 17) for the build tools under
 * `tools/`, with `kotlin-test` for its tests. `verify` compiles it and runs its tests.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(OpenSensorConventions.JAVA_RELEASE)
            targetCompatibility = JavaVersion.toVersion(OpenSensorConventions.JAVA_RELEASE)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(OpenSensorConventions.jvmTarget)
                openSensorDefaults()
            }
        }
        dependencies.add("testImplementation", catalogLibrary("kotlin-test"))
        // `assemble` (and so GitHub CodeQL autobuild) compiles these modules, so `verify` does too.
        addToGate(Gates.HOST, "compileKotlin", "test")
    }
}

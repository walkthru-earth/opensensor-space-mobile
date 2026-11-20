import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * `opensensor.android.application`. The Android app module, `:app`, on AGP's built-in Kotlin.
 *
 * - SDK levels from [OpenSensorConventions], the same numbers as every shared Android library target.
 * - Java and Kotlin bytecode 17, the same as the shared modules. Without it AGP compiles `:app` to
 *   its own default, Java 11.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk { openSensorCompileSdk() }
            defaultConfig {
                minSdk = OpenSensorConventions.MIN_SDK
                targetSdk = OpenSensorConventions.TARGET_SDK
            }
            compileOptions {
                val java = JavaVersion.toVersion(OpenSensorConventions.JAVA_RELEASE)
                sourceCompatibility = java
                targetCompatibility = java
            }
        }
        tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(OpenSensorConventions.jvmTarget)
        }
    }
}

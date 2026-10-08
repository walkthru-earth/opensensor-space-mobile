// Common Kotlin is compiled for Android's JVM and for iOS. Legacy file/stream adapters stay JVM-only.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest { dependencies { implementation(libs.kotlin.test) } }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// Keep existing verify tasks, local hooks and CI entry points compatible.
tasks.register("test") { dependsOn("jvmTest") }

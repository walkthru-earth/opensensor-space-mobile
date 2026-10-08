// Common Kotlin is compiled for Android's JVM and for iOS. Legacy file/stream adapters stay JVM-only.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core:protocol"))
            }
        }
        commonTest { dependencies { implementation(libs.kotlin.test) } }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies { api(project(":core:parquet")) }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir(rootProject.file("core/parquet/src/test/resources"))
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// Keep existing verify tasks, local hooks and CI entry points compatible.
tasks.register("test") { dependsOn("jvmTest") }

// The Discover map (docs/devices-and-discovery.md Part 2). Shared Compose in `commonMain` for the
// Android app and the iOS framework. It targets Android directly (not the JVM like :core:*),
// because maplibre-compose ships a separate Android artifact and its desktop JVM variant needs
// Java 25. The bundled sample feed is a Compose resource (tools/discover-fixture).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "earth.walkthru.opensensor.discover"
        compileSdk = 37
        minSdk = 31
        androidResources { enable = true }
        withHostTest {}
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:publicfeed"))
            // DiscoverScreen takes the resolved UnitSystem from its host.
            api(project(":core:units"))
            api(project(":ui:designsystem"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.maplibre.compose)
        }
        androidMain.dependencies {
            // One render backend for Android. OpenGL first, Vulkan after a phone check.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
            // The "Near me" coarse location prompt (NearMe.android.kt).
            implementation(libs.androidx.activity.compose)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "earth.walkthru.opensensor.discover.resources"
    generateResClass = always
}

// The opensensor.space design system, shared by the Android app, the iOS framework and every
// Compose feature module. Brand palette, shapes and the floating map control style live here once
// so both platforms look the same (docs/screens.md, "Design system").
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "earth.walkthru.opensensor.designsystem"
        compileSdk = 37
        minSdk = 31
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            api(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}

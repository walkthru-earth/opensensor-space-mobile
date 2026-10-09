// Convention plugins. The Kotlin, Android and Compose Gradle plugins are compileOnly: the root build
// declares them once (`apply false`), so every module and these conventions share the same plugin
// classloader.
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "opensensor.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "opensensor.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("iosFramework") {
            id = "opensensor.kmp.ios-framework"
            implementationClass = "IosFrameworkConventionPlugin"
        }
        register("composeFeature") {
            id = "opensensor.kmp.compose-feature"
            implementationClass = "ComposeFeatureConventionPlugin"
        }
    }
}

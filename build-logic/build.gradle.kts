// Convention plugins. The Kotlin Gradle plugin is compileOnly: the root build declares it once
// (`apply false`), so every module and these conventions share the same plugin classloader.
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
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
    }
}

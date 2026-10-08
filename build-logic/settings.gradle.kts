// Convention plugins for the shared modules. Included from the root settings through pluginManagement.
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"

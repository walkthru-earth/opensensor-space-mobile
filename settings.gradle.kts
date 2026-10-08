pluginManagement {
    // Convention plugins for the shared modules (see build-logic/ and docs/architecture.md).
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "opensensor-space-mobile"

include(":app")
// Protocol, Parquet and history are Kotlin Multiplatform, shared with iOS.
include(":core:protocol")
include(":core:parquet")
include(":core:history")

include(":ios:shared")

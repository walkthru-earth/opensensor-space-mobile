pluginManagement {
    // Convention plugins for the shared modules (see build-logic/ and docs/architecture.md).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
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
// Protocol and sensor-domain logic are shared with iOS; Parquet and file-backed history remain JVM.
include(":core:protocol")
include(":core:parquet")
include(":core:history")

include(":ios:shared")

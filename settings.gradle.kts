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
// The public Discover feed: port, H3, viewport cover, bundled sample (docs/devices-and-discovery.md).
include(":core:publicfeed")
// Display units, metric or imperial, for every screen on both platforms (docs/screens.md, Settings).
include(":core:units")
// WHO 2021 air quality guideline scales and their palette, for every chart and the map.
include(":core:airquality")
// Planned shared sync engine, phone archive and app settings (docs/platform-parity-plan.md).
include(":core:sync")
include(":core:archive")
include(":core:settings")
// The Discover map, shared Compose for Android and iOS.
include(":ui:designsystem")
// The shared English copy and the repository copy lint (docs/screens.md, Copy).
include(":ui:strings")
include(":feature:discover")
// The Settings screen, shared Compose for Android and iOS (docs/platform-parity-plan.md).
include(":feature:settings")

include(":ios:shared")

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

// Opt-in host history benchmark, no device archive or hardware access.
include(":tools:history-benchmark")

include(":app")
// Protocol, Parquet and history are Kotlin Multiplatform, shared with iOS.
include(":core:protocol")
include(":core:parquet")
include(":core:history")
// The shared model of what a thing measures: board templates, app profiles, datastreams, scales (#183).
include(":core:model")
// The public Discover feed: port, H3, viewport cover, bundled sample (docs/devices-and-discovery.md).
include(":core:publicfeed")
// Display units, metric or imperial, for every screen on both platforms (docs/screens.md, Settings).
include(":core:units")
// WHO 2021 air quality guideline scales and their palette, for every chart and the map.
include(":core:airquality")
// HTTP range reads for remote Parquet, OkHttp on Android and NSURLSession on iOS (issue #62).
include(":core:net")
// The shared sync engine (docs/sync-engine.md).
include(":core:sync")
include(":core:archive")
// The one Room database of both phones on the bundled SQLite driver (docs/data-model.md, #100).
include(":core:database")
// Storage classes, durable no-replace commits, free space and the startup janitor's walk (issue #60).
include(":core:storage")
include(":core:settings")
// One STAC File Item per synced device file, the sidecar the upload will publish (#190).
include(":core:stac")
// The Thing kinds each app lists, the opensensor board kind and the PublishGate in front of FileItems (#219).
include(":core:thing")
// The ThingKindContract every kind passes from its own tests.
include(":core:thing:testkit")
// The Discover map, shared Compose for Android and iOS.
include(":ui:designsystem")
// The shared English copy and the repository copy lint (docs/screens.md, Copy).
include(":ui:strings")
// When each permission is asked and where its settings link goes, for both apps.
include(":ui:permissions")
include(":feature:discover")
// The Settings screen, shared Compose for Android and iOS (docs/screens.md).
include(":feature:settings")
// My devices, one shared list of nearby, known and Wi-Fi sensors (docs/screens.md, My devices).
include(":feature:devices")
// The Files screen, its inspector and the export, shared Compose for Android and iOS (docs/screens.md).
include(":feature:files")
// The History screen and its charts, shared Compose for Android and iOS (docs/history-pipeline.md).
include(":feature:history")
// The Today screen of one device, shared Compose for Android and iOS (docs/screens.md).
include(":feature:today")
// The Device section of one sensor, its configuration cards and confirmations, shared Compose for Android and iOS.
include(":feature:device")
// The setup wizard, one step list per Thing kind, shared Compose for Android and iOS (docs/screens.md, Add sensor).
include(":feature:setup")
// The Debug screen, shared Compose for Android and iOS, Developer level only (docs/screens.md, Debug).
include(":feature:debug")
// The app shell: bottom bar, per-tab Navigation 3 back stacks, Back rules (docs/adr/0001-shared-navigation.md).
include(":feature:shell")

include(":ios:shared")

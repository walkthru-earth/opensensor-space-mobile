# Build logic

The convention plugins every module applies. Change targets, bytecode level, test wiring or gate
membership here, once, never in a module build file. See `../docs/build-and-release.md` and
`../docs/architecture.md`.

| Plugin id | Class | For |
| --- | --- | --- |
| `opensensor.kmp.library` | `KmpLibraryConventionPlugin` | A module for the JVM and iOS. Every `:core` module except `:core:database`, and `:family:air` |
| `opensensor.kmp.android-library` | `KmpAndroidLibraryConventionPlugin` | A module for Android and iOS with no Compose and no JVM target. `:core:database` (Room) |
| `opensensor.kmp.compose-feature` | `ComposeFeatureConventionPlugin` | A shared Compose module for Android and iOS. Every `:feature` and `:ui` module |
| `opensensor.kmp.ios-framework` | `IosFrameworkConventionPlugin` | The iOS only module that builds the static `OpenSensorShared` framework. `:ios:shared` |
| `opensensor.jvm.library` | `JvmLibraryConventionPlugin` | A plain Kotlin JVM module. `:tools:history-benchmark` |

## Rules

- `build.gradle.kts` registers the plugin ids. `OpenSensorConventions.kt` holds the settings all
  of them share, such as the JVM bytecode target.
- `Gates.kt` names the two root gates, `verify` (`Gates.HOST`) and `verifyIos` (`Gates.IOS`). The
  root build script registers both tasks, and each plugin adds its own module tasks with
  `addToGate`. A new module that applies a convention is gated at once and needs no list edit.
- `gateMetadataCompiles` adds the metadata compile of every shared source set. A source set only
  the iOS targets share joins `verifyIos`, the rest join `verify`.
- `iosBuildsEnabled` is true on a Mac with an iOS SDK and without `opensensor.skipIos=true`. The
  root build script reads it to decide whether `verify` runs `verifyIos`, and module build scripts
  read it to leave out iOS only work, such as the H3 C library build of `:core:publicfeed`.
- The Kotlin, Android and Compose Gradle plugins are `compileOnly` here. The root build declares
  them once, so every module and these conventions share one plugin classloader.
- Each plugin gives its module a `test` task, so hooks and CI use one task name per module.

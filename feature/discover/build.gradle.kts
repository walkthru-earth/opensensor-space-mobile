// The Discover map (docs/devices-and-discovery.md Part 2). Shared Compose in `commonMain` for the
// Android app and the iOS framework. The map itself, its styles, zoom, tile cache and Near me come
// from :ui:map. Discover draws the public cells, own pins and legend on it. The bundled sample feed
// is a Compose resource (tools/discover-fixture).
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:publicfeed"))
            // DiscoverScreen takes the resolved UnitSystem from its host.
            api(project(":core:units"))
            // DiscoverScreen takes the host's DetailLevel (Simple speaks in moods).
            api(project(":core:settings"))
            api(project(":ui:designsystem"))
            // The shared map, its camera rest, zoom controls and Near me.
            api(project(":ui:map"))
            implementation(libs.kotlinx.coroutines.core)
            // Back first returns an open sheet to peek, on both apps (NavigationBackHandler).
            implementation(libs.jetbrains.navigationevent.compose)
        }
        commonTest.dependencies {
            // DiscoverControllerTest drives the controller on a test scheduler.
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies {
            // The window slider and sheet semantics tests render on the iOS simulator.
            implementation(libs.compose.ui.test)
        }
    }
}

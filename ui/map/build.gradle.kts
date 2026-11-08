// The shared MapLibre map of both apps (docs/architecture.md). The base map with its camera limits
// and camera rest callback, the bundled basemap and paper styles, zoom controls, the tile cache, the
// plain H3 cell outlines and Near me. Callers draw their own sources and layers in the map's content
// slot. Discover is the first caller, the cell picker of setup and Device settings come next. It
// targets Android directly (not the JVM like :core:*), because maplibre-compose ships a separate
// Android artifact and its desktop JVM variant needs Java 25.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // H3Grid, LatLng and GeoBounds, the plain types of the camera and the cell outlines.
            api(project(":core:publicfeed"))
            api(project(":ui:designsystem"))
            // Near me follows the shared permission policy and its settings link.
            api(project(":ui:permissions"))
            // Callers draw their layers in the map's content slot and drive its MapState.
            api(libs.maplibre.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // One render backend for Android. OpenGL first, Vulkan after a phone check.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
            // The "Near me" coarse location prompt (NearMe.android.kt).
            implementation(libs.androidx.activity.compose)
        }
        iosTest.dependencies {
            // The zoom controls semantics test renders on the iOS simulator.
            implementation(libs.compose.ui.test)
        }
    }
}

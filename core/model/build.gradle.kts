// The shared core model of what a station measures (#183): board templates, app profiles, datastreams,
// observed properties, scales and the station that joins them. Pure commonMain with kotlinx
// serialization parsing of the stac-opensensor v0.1.0 board template, the app's own profile JSON and
// the station Collection. No callers yet, the screens and history move onto it from #184 onward.
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

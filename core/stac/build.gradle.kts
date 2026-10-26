// One STAC File Item per synced device file, as the stac-opensensor v0.1.0 profile describes it
// (#190). A pure function over the verified file bytes, its footer and its board template. It never
// uploads, reads Room or touches the network. Geometry comes from H3 cells only, never coordinates.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The verified file, its footer and its UTC column readers.
            api(project(":core:history"))
            // H3 cell polygons and the union of visited cells.
            api(project(":core:publicfeed"))
            // SHA-256 for file:checksum.
            implementation(project(":core:protocol"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // Items validate against the profile and extension JSON schemas copied from stac-opensensor.
            implementation(libs.json.schema.validator)
        }
    }
}

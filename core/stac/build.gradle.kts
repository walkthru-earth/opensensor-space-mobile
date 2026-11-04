// The STAC File Item of a synced device file, as the stac-opensensor v0.1.0 profile describes it
// (#190). The board writes the Item, the phone regenerates it to check it. A pure function over the
// verified file bytes, its footer and its board template. It never uploads, reads Room or touches the
// network. Geometry comes from H3 cells only, never coordinates.
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

// The files copied from stac-opensensor live once, under src/commonTest/fixtures. The common tests read
// them on the JVM and in the simulator, which sees the host file system. `SIMCTL_CHILD_` variables
// reach the simulator process without the prefix.
val stacFixtureRoot = layout.projectDirectory.dir("src/commonTest/fixtures/stac-opensensor").asFile
tasks.withType<Test>().configureEach {
    systemProperty("stac.fixture.root", stacFixtureRoot.absolutePath)
    inputs.dir(stacFixtureRoot).withPropertyName("stacFixtures")
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_STAC_FIXTURE_ROOT", stacFixtureRoot.absolutePath)
    inputs.dir(stacFixtureRoot).withPropertyName("stacFixtures")
}

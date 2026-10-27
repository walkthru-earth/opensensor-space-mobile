import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The air family (#188). Everything about particulate air quality lives here and nowhere in :core:*.
// The WHO 2021 guideline scales with their interim targets and the one colour-blind checked palette,
// the EPA PM2.5 correction and the derived metrics it feeds (AirFamily), the moods and the Now card,
// the named air metrics, the PM sensor status and sticker, the Plantower batch estimate, the public
// cell summary, the EPA NowCast of the last 12 hours (PmNowCast, #93) and the pure quality checks
// for suspect PM readings (QcRules over a rolling live window in LiveQc, #91).
// Core reaches it only through the generic seams the app installs (DerivedMetricProvider,
// DiscoverCollection.installBundled). Pure commonMain for JVM and iOS.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // DerivedMetricProvider, HealthScale, Thing and the board templates.
            api(project(":core:model"))
            // LiveReading, DeviceInfo and the config patch builder.
            api(project(":core:protocol"))
            // MetricSpec, Metrics and DeviceCapabilities, which the air metrics extend.
            api(project(":core:history"))
            // StepScale and the feed rows the public summary reads.
            api(project(":core:publicfeed"))
            // formatFixed, which formatPm rounds with.
            implementation(project(":core:units"))
        }
        // Tests only. The quality checks also run over a real device archive, read with the portable reader.
        commonTest.dependencies {
            implementation(project(":core:parquet"))
        }
    }
}

dependencies {
    // Real device Parquet files, published once by :core:parquet.
    "jvmTestImplementation"(testFixtures(project(":core:parquet")))
}

// The simulator test binary reads the same :core:parquet fixtures straight from this checkout (a
// simulator process sees the host file system). `SIMCTL_CHILD_` variables reach it without the prefix.
val parquetFixtures = rootProject.layout.projectDirectory.dir("core/parquet/src/test/resources")
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_PARQUET_FIXTURES", parquetFixtures.asFile.absolutePath)
    inputs.dir(parquetFixtures).withPropertyName("parquetFixtures")
}

// CoreStaysAirFreeTest reads every production source under core/ and fails on an air term there.
val coreSources = rootProject.layout.projectDirectory.dir("core")
tasks.named<Test>("jvmTest") {
    systemProperty("opensensor.coreDir", coreSources.asFile.absolutePath)
    inputs.files(fileTree(coreSources) { include("**/src/*Main/**/*.kt", "**/build.gradle.kts"); exclude("**/build/**") })
        .withPropertyName("coreSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

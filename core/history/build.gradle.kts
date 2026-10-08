import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// Sensor-domain analysis, file indexing, aggregation and the calendar windows of the History chart.
// `commonMain` compiles for Android's JVM and for iOS: Parquet comes from the portable :core:parquet
// reader and local dates and time zones from kotlinx-datetime.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(project(":core:parquet"))
            // WHO 2021 health scales (edges, steps, colours) are part of the MetricSpec and AirMood API.
            api(project(":core:airquality"))
            // TimeZone and LocalDate are part of the public HistoryWindow and Columns API.
            api(libs.kotlinx.datetime)
            // Query switching in ArchiveQueries is a Flow operator.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

dependencies {
    // Real device Parquet files and PyArrow expectations, published once by :core:parquet.
    "jvmTestImplementation"(testFixtures(project(":core:parquet")))
}

// The simulator test binary reads the same :core:parquet fixtures straight from this checkout (a
// simulator process sees the host file system). `SIMCTL_CHILD_` variables reach it without the prefix.
val parquetFixtures = rootProject.layout.projectDirectory.dir("core/parquet/src/test/resources")
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_PARQUET_FIXTURES", parquetFixtures.asFile.absolutePath)
    inputs.dir(parquetFixtures).withPropertyName("parquetFixtures")
}

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
            // The bundled board templates, which say whether a board writes the batch estimate (#184).
            api(project(":core:model"))
            api(project(":core:parquet"))
            // History reads the archive through its records (ArchiveRecord) and, later, its ports.
            api(project(":core:archive"))
            // The Hive partition parser names a file's producer thing, which index repair checks.
            api(project(":core:sync"))
            // HistoryStartup applies the feed block quota of the public feed cache after the janitor.
            api(project(":core:net"))
            // TimeZone and LocalDate are part of the public HistoryWindow and Columns API.
            api(libs.kotlinx.datetime)
            // Query switching in ArchiveQueries is a Flow operator.
            api(libs.kotlinx.coroutines.core)
            // The `opensensor` footer value is JSON.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest {
            // The device fixture footer migration, shared with the :app unit tests.
            kotlin.srcDir("src/legacyFooterFixtures/kotlin")
        }
        commonTest.dependencies {
            // Tests only. The board fixtures are air boards, so their metrics come from the air family.
            implementation(project(":family:air"))
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

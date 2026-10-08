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
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    // Real device Parquet files and PyArrow expectations, published once by :core:parquet.
    "jvmTestImplementation"(testFixtures(project(":core:parquet")))
}

// Sensor-domain analysis. `commonMain` compiles for Android's JVM and for iOS; `jvmMain` keeps the
// file-backed indexing and aggregation that still read Parquet through :core:parquet.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
        }
        jvmMain.dependencies {
            api(project(":core:parquet"))
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

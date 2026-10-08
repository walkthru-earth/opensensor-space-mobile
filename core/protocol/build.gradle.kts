// The firmware contract mirror. `commonMain` compiles for Android's JVM and for iOS; `jvmMain` keeps
// the java.io/java.security adapters (file verification, SHA-256, LAN framing over streams).
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    // Real device Parquet files for the CRC-32 and verification equivalence test.
    "jvmTestImplementation"(testFixtures(project(":core:parquet")))
}

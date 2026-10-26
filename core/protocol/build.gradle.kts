// The firmware contract mirror. `commonMain` compiles for Android's JVM and for iOS; `jvmMain` keeps
// the java.io adapters (file verification and hashing, LAN framing over streams) and the
// MessageDigest SHA-256. `iosMain` holds the CommonCrypto SHA-256.
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // The bundled board templates and app profiles that LIVE profiles come from (#184).
            api(project(":core:model"))
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

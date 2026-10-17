// HTTP range reads for remote Parquet (issue #62). `commonMain` holds `HttpRangeSource`, the
// `RangeSource` over an `HttpRangeEngine` (one GET with Range, If-Range and If-None-Match).
// `CachedRangeSource` keeps the fetched blocks in a `BlockCache` on disk inside the rebuildable cache
// quota (#94). `jvmMain` binds the engine to OkHttp 5 for Android and the JVM. `iosMain` binds it to
// NSURLSession.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:parquet"))
            api(libs.kotlinx.coroutines.core)
            // BlockCache writes through AtomicFiles into the FeedBlocks root.
            api(project(":core:storage"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // The app builds the OkHttpClient it hands to OkHttpRangeEngine.
            api(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

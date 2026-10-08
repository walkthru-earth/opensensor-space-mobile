// The public Discover feed (docs/devices-and-discovery.md Part 2). `commonMain` holds the feed port,
// the H3 interface, the viewport cover, the bundled sample source and the colour scale. `jvmMain`
// binds H3 to com.uber:h3 and reads Parquet through the JVM :core:parquet reader. `iosMain` binds
// H3 to the H3 C library through cinterop. Parquet on iOS waits for W8's portable reader.
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(project(":core:parquet"))
            implementation(libs.h3)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The bundled sample feed lives once, as Compose resources of :feature:discover. JVM tests read it
// from there so the files the app ships are the files the tests check.
tasks.withType<Test>().configureEach {
    systemProperty("discover.sample.root", rootProject.file("feature/discover/src/commonMain/composeResources/files/discover-sample").absolutePath)
}

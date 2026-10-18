import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// Health scales for air quality values: the WHO 2021 Air Quality Guidelines with their interim
// targets, and the one colour-blind checked palette that paints them, plus the pure quality checks
// for suspect PM readings (QcRules, #91). Pure Kotlin, no dependencies, so :core:history,
// :core:publicfeed, :feature:discover, :app and :ios:shared all reach it.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
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

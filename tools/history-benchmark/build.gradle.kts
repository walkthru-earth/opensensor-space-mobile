plugins {
    id("opensensor.jvm.library")
    alias(libs.plugins.kotlinx.benchmark)
}

dependencies {
    implementation(project(":core:history"))
    implementation(libs.kotlinx.benchmark.runtime)
}

// Use the frozen pre-change oracle also exercised by HistoryGoldenTest, and the firmware footer fixture.
kotlin.sourceSets.main {
    kotlin.srcDir("../../core/history/src/commonTest/kotlin")
    kotlin.srcDir("../../core/history/src/legacyFooterFixtures/kotlin")
    kotlin.include(
        "earth/walkthru/aqsync/data/history/LegacyHistoryPipeline.kt",
        "earth/walkthru/aqsync/data/history/HistoryBenchmark.kt",
        "earth/walkthru/aqsync/data/history/FirmwareFooterFixture.kt",
    )
}

benchmark {
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ns"
            mode = "avgt"
        }
    }
    targets { register("main") }
}

// Include the Kotlin target classes as well as the generated JMH classes.
tasks.withType<Jar>().matching { it.name == "mainBenchmarkJar" }.configureEach {
    from(sourceSets.main.get().output)
}

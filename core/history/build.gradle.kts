// Analysis logic over archived files: file index, metric registry, aggregation into tiers, time estimate
// for unsynced boots, chart binning, the friendly layer. Pure JVM; depends only on :core:parquet and
// :core:protocol. Anything that touches Room, files on the phone or Android stays in :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:parquet"))
    api(project(":core:protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

// The real-file fixtures live once, in :core:parquet.
sourceSets.test { resources.srcDir(rootProject.file("core/parquet/src/test/resources")) }

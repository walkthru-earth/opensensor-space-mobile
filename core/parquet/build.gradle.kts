// The Parquet subset the CoreS3 firmware writes: reader (memory-mapped, column projection, primitive
// arrays), writer (statistics, TIMESTAMP, LZ4_RAW), Thrift compact codec and LZ4 block codec. Pure JVM.
// Fixtures in src/test/resources/parquet are produced by tools/make_parquet_fixtures.py (PyArrow oracle).
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

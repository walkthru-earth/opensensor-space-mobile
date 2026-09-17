// The Parquet subset the CoreS3 firmware writes, plus GeoParquet 2.0 (docs/history-ux-and-data-layer.md §7):
// reader (memory-mapped, column projection, primitive arrays; PLAIN + dictionary encodings, Data Page V1/V2,
// UNCOMPRESSED/LZ4_RAW/SNAPPY/GZIP built in, ZSTD through the codec SPI), writer (statistics, TIMESTAMP,
// GEOMETRY, LZ4_RAW), Thrift compact codec, LZ4 block codec, Snappy decoder, WKB. Pure JVM: kotlinx.serialization
// only (for the `geo` key). Fixtures in src/test/resources/{parquet,geoparquet} are produced by
// tools/make_parquet_fixtures.py and tools/make_geoparquet_fixtures.py (PyArrow + DuckDB oracles).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // ZSTD pages in the tests only: the module itself never links it; :app supplies the same library as an AAR.
    testImplementation(libs.zstd.jni)
}

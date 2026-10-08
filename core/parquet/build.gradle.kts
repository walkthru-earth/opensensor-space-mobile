// The Parquet subset the CoreS3 firmware writes, plus GeoParquet 2.0 (docs/parquet.md):
// reader (memory-mapped, column projection, primitive arrays; PLAIN + dictionary encodings, Data Page V1/V2,
// UNCOMPRESSED/LZ4_RAW/SNAPPY/GZIP built in, ZSTD through the codec SPI), writer (statistics, TIMESTAMP,
// GEOMETRY, LZ4_RAW), Thrift compact codec, LZ4 block codec, Snappy decoder, WKB. Pure JVM: kotlinx.serialization
// only (for the `geo` key).
plugins {
    id("opensensor.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// Fixtures in src/test/resources/{parquet,geoparquet} are produced by tools/make_parquet_fixtures.py and
// tools/make_geoparquet_fixtures.py (PyArrow + DuckDB oracles). They stay in that one directory and are
// published as this module's test fixtures, so other modules declare `testFixtures(project(":core:parquet"))`
// instead of reaching into this directory. This module's own tests receive them the same way.
sourceSets {
    named("testFixtures") { resources.setSrcDirs(listOf("src/test/resources")) }
    named("test") { resources.setSrcDirs(emptyList<String>()) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // ZSTD pages in the tests only: the module itself never links it; :app supplies the same library as an AAR.
    testImplementation(libs.zstd.jni)
}

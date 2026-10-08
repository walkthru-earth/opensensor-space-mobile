import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The Parquet subset the CoreS3 firmware writes, plus GeoParquet 2.0 (docs/parquet.md): reader (column
// projection, primitive arrays; PLAIN + dictionary encodings, Data Page V1/V2, UNCOMPRESSED/LZ4_RAW/SNAPPY/
// GZIP built in, ZSTD through the codec SPI), writer (statistics, TIMESTAMP, GEOMETRY, LZ4_RAW), Thrift
// compact codec, LZ4 block codec, Snappy decoder, WKB. `commonMain` compiles for Android's JVM and for iOS
// and reads bytes through `ByteReader` / `SuspendByteReader`; `jvmMain` maps files with `FileChannel`,
// inflates GZIP with java.util.zip and adapts `OutputStream`; `iosMain` maps files as `NSData` and inflates
// with the system zlib. kotlinx.serialization only (for the `geo` key).
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // ZSTD pages in the tests only: the module itself never links it; :app supplies the same library as an AAR.
            implementation(libs.zstd.jni)
        }
        // This module's own JVM tests read the shared fixtures from the classpath, like every consumer.
        jvmTest { resources.srcDir("src/test/resources") }
    }
}

// Fixtures in src/test/resources/{parquet,geoparquet} are produced by tools/make_parquet_fixtures.py and
// tools/make_geoparquet_fixtures.py (PyArrow + DuckDB oracles). They stay in that one directory and are
// published as this module's test fixtures, so other modules declare `testFixtures(project(":core:parquet"))`
// instead of reaching into this directory. The `java-test-fixtures` plugin needs the `java` plugin, which a
// Kotlin Multiplatform module does not apply, so the variant it would publish is declared here by hand:
// a resources jar with the `<group>:parquet-test-fixtures` capability that `testFixtures(...)` asks for.
val fixtureDir = layout.projectDirectory.dir("src/test/resources")
val testFixturesJar = tasks.register<Jar>("testFixturesJar") {
    archiveClassifier.set("test-fixtures")
    from(fixtureDir)
}
configurations.consumable("testFixturesRuntimeElements") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
    outgoing {
        capability("${project.group}:${project.name}-test-fixtures:${project.version}")
        artifact(testFixturesJar)
    }
}

// The simulator test binary reads the same fixtures straight from this checkout (a simulator process
// sees the host file system). `SIMCTL_CHILD_` variables reach the spawned process without the prefix.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_PARQUET_FIXTURES", fixtureDir.asFile.absolutePath)
    inputs.dir(fixtureDir).withPropertyName("parquetFixtures")
}

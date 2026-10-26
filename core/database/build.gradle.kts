// The one Room database of both phones (docs/data-model.md, #100). Entities, DAOs, migrations and the
// Room adapters of the archive ports (`RoomArchiveIndex`, `RoomPrunedFiles`) live in `commonMain`.
// Android and iOS open the same schema, version and file format through the bundled SQLite driver.
// iosMain also holds the one time import of the older iOS receipts. This is the only module with
// Room, so it targets Android and iOS (Room ships separate artifacts) and has no JVM target.
plugins {
    id("opensensor.kmp.android-library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ArchiveIndex, PrunedFiles and ArchiveRecord are the ports this module adapts.
            api(project(":core:archive"))
            // IndexedFileInfo (ArchivedFile.info) for the Android History passes.
            api(project(":core:history"))
            // KnownDevice is a BackgroundSyncDevice.
            api(project(":core:sync"))
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidHostTest.dependencies {
            // The bundled driver's natives for the host JVM, as the Android unit tests use.
            implementation(libs.androidx.sqlite.bundled.jvm)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

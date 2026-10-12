// The shared phone archive (docs/data-model.md, `:core:archive`). One archive layout (Hive
// paths, partial and legacy receipt names), the file verifier (size, CRC-32, PAR1), the plain records
// both platforms store, and the store ports (`ArchiveStore`, `ArchiveIndex`). Platform adapters do file
// and database I/O only. No Android, Hilt or Room here (AGENTS.md).
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // SafeName, ParquetVerify and Crc32 are part of the key and verifier API.
            api(project(":core:protocol"))
            // ByteReader is how the verifier and the stores hand out file bytes.
            api(project(":core:parquet"))
            // Every archive write goes through AtomicFiles, and a commit can throw AlreadyCommitted (#60).
            api(project(":core:storage"))
            // ArchiveIndex observes rows as a Flow.
            api(libs.kotlinx.coroutines.core)
            // UTC day labels of the derived files.
            implementation(libs.kotlinx.datetime)
        }
        iosMain.dependencies {
            // The iOS receipt index reads and writes `<name>.verified.json` as plain JSON objects.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

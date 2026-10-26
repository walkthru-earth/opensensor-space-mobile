// The shared sync engine (docs/sync-engine.md). The link session and its timeouts, the device, token
// and resume policies, the planner, the window retry rule, progress and rate maths, `SyncEngine`,
// `ArchiveCollector` and the per-device `CollectionController`, run by Android and iOS over the
// `:core:archive` store ports. Classes keep their `space.opensensor.data.*` packages.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Frames, opcodes and JSON models are part of the session API.
            api(project(":core:protocol"))
            // The engine writes through the archive ports and verifies with ArchiveVerifier.
            api(project(":core:archive"))
            // Flow, StateFlow and Channel are part of the transport and session API.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

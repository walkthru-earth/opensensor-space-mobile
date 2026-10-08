// The shared sync engine (docs/platform-parity-plan.md, target shape). Wave 2 moved the pure parts
// of Android's stack here: the request session and its timeouts, the device, token and resume
// policies, the planner, the window retry rule and the progress and rate maths. Moved classes keep
// their `earth.walkthru.aqsync.data.*` packages so no caller changed. Wave 3 puts the engine behind
// the FrameLink, ArchiveStore, ArchiveIndex and CredentialStore ports for Android and iOS.
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

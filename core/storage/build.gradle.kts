// One storage layer for every writer on both phones (issue #60). Storage classes and their roots,
// durable no-replace commits through `AtomicFiles`, free space through `StorageBudget`, and the plain
// directory walk the startup janitor sweeps with. Platform adapters do the system calls only. No
// Android, Hilt or Room here (AGENTS.md).
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // StorageBudget is suspend and runs file walks off the caller's thread.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

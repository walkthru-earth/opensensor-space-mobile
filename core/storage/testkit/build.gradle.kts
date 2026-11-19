// FakeStorageBudget, the settable free space the sync and storage tests of both phones run against.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:storage"))
        }
    }
}

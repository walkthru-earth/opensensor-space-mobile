// ThingKindContract, the checks every ThingKind passes, in commonMain so a kind's own commonTest runs
// them on the JVM and the iOS simulator (spec core.md section 8).
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:thing"))
            api(libs.kotlin.test)
        }
    }
}

// The demo sensor both phones open in debug builds (#310). An in-process board behind the same
// `DeviceTransport` port the Bluetooth and LAN links use, so every screen and the whole sync path
// run without hardware. Its LIVE values come from the bundled board template, so this module names
// no sensor family. Release builds never link it (docs/build-and-release.md, Demo sensor).
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // DeviceTransport, ConnectionState and DeviceSession, and through it the protocol frames.
            api(project(":core:sync"))
            // The bundled board template the demo board serves and reads its LIVE keys from.
            api(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

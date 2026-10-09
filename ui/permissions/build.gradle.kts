// The permission policy both apps follow (docs/store-compliance.md, "Permission timing"). One table
// from user action to permission, the status model with iOS UNKNOWN for local network, the ask,
// rationale and settings decisions, and the shared dialogs. The OS seams (the Android launchers in
// `:app`, Core Location and Core Bluetooth on iOS) stay on their platforms. Opening the app's
// system settings page is the one expect/actual here.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The dialog button labels a caller leaves out come from the shared catalogue.
            implementation(project(":ui:strings"))
        }
    }
}

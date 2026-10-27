// The Thing plugin contract (#219): ThingKind, its DataSource and PublishPolicy, the ThingKinds
// registry each app entry point builds as a plain list, the opensensor-board kind (#222), and the
// PublishGate, the one caller of FileItems (#224). Pure commonMain for JVM and iOS, no network.
plugins {
    id("opensensor.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Capability, DerivedMetricProvider and the board templates.
            api(project(":core:model"))
            // DeviceInfo and the advertised BLE service and mDNS type the board kind recognizes.
            api(project(":core:protocol"))
            // DeviceCapabilities and Metrics, which takes the kinds' derived metric providers.
            api(project(":core:history"))
            // FileItems, which only PublishGate calls.
            api(project(":core:stac"))
            // The public feed a RemoteFeed kind reads.
            api(project(":core:publicfeed"))
        }
        commonTest.dependencies {
            implementation(project(":core:thing:testkit"))
            // The air family as a real provider the kinds feed into the catalog, test only.
            implementation(project(":family:air"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

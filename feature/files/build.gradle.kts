// The Files screen and the per-file inspector (docs/screens.md, Files and File metrics), shared Compose
// in `commonMain` for the Android app and the iOS framework. FilesController lists the phone archive
// and stages exports through the `:core:archive` ports. The share sheet and the save picker are the
// only platform code (ExportPresenter, androidMain and iosMain). Android adds the device listing,
// sync and its own menu items through parameters.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            // The inspector opens files with ParquetFile and summarises them with ParquetIndexer.
            api(project(":core:history"))
            // ManifestState, SyncPlan and SyncProgress are part of the screen's device input.
            api(project(":core:sync"))
            // DetailLevel.
            api(project(":core:settings"))
            api(project(":ui:designsystem"))
            implementation(project(":ui:strings"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            // The save picker (CreateDocument) and the share chooser.
            implementation(libs.androidx.activity.compose)
            // Keeps an open save picker's request across activity recreation (PendingSave).
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            // FileProvider.
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The opensensor.space design system, shared by the Android app, the iOS framework and every
// Compose feature module. Brand palette, shapes, the bundled Quicksand and Cairo fonts with the type
// scale, the floating control style, the shared components (cards, buttons, rows, info sheet,
// signal bars, the bottom bar) and the vendored Material icons (AppIcons) live here once so both
// platforms look the same (docs/screens.md, "Design system"). Fonts, their OFL texts, the peacock
// and the component copy are Compose resources.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Feature modules style their screens with the Material 3 types this module themes.
            api(libs.compose.material3)
        }
    }
}

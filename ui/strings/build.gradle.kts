import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

// The shared English copy for the iOS companion screen and the shared screens to come, one Compose
// resource catalogue for both apps (docs/screens.md, Copy and the info affordance). Keys that
// Android `strings.xml` already has keep the same name and the same words.
// The host tests here are the repository copy lint. CopyLintTest reads every Compose resource and
// Android `strings.xml` in the repository, and PresentationLiteralsTest keeps English literals out
// of the shared presentation code (iOS, permissions, features). Both join `verify` through the convention plugin.
// opensensor.kmp.compose-feature sets the Android and iOS targets, Compose and the resources package.
plugins {
    id("opensensor.kmp.compose-feature")
}

// Other modules read this catalogue, so its Res class is public (the convention keeps it internal).
compose.resources {
    publicResClass = true
}

// TranslationsTest reads the iOS purpose string catalogue (InfoPlist.xcstrings) as JSON.
kotlin {
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.kotlinx.serialization.json)
    }
}

// The host audits read other modules' source files rather than their compiled classes. Declare
// those files so edits invalidate an earlier passing test result locally and in the build cache.
val copyAuditInputs = rootProject.fileTree(rootProject.projectDir) {
    include(
        "**/src/*/res/values*/strings.xml",
        "**/src/*/composeResources/values*/strings.xml",
        "feature/*/src/commonMain/kotlin/**/*.kt",
        "ios/shared/src/commonMain/kotlin/**/*.kt",
        "ios/shared/src/iosMain/kotlin/**/*.kt",
        "ui/designsystem/src/commonMain/kotlin/**/*.kt",
        "ui/permissions/src/commonMain/kotlin/**/*.kt",
        "app/src/main/res/xml/locales_config.xml",
        "app/src/main/AndroidManifest.xml",
        "ios/App/Info.plist",
        "ios/App/InfoPlist.xcstrings",
        "ios/OpenSensor.xcodeproj/project.pbxproj",
    )
    exclude("**/build/**", "**/node_modules/**", "**/DerivedData/**", ".gradle/**", ".git/**", ".claude/**", ".idea/**", ".kotlin/**")
}

tasks.withType<Test>().configureEach {
    inputs.files(copyAuditInputs)
        .withPropertyName("repositoryCopyAndPresentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

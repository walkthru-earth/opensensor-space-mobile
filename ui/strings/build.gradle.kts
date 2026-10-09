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

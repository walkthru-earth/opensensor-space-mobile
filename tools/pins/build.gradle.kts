// Drift checks of version pins that live outside the version catalog: the ktlint hook, the firmware
// commit in the workflows, the CI pre-commit pin and the vendored H3 C library. Tests only. They read
// the checkout and join `verify` through the JVM convention (docs/build-and-release.md).
plugins {
    id("opensensor.jvm.library")
}

val repoDir: File = rootDir
tasks.test {
    systemProperty("opensensor.repoDir", repoDir.absolutePath)
    inputs.files(
        "$repoDir/gradle/libs.versions.toml",
        "$repoDir/tools/hooks/ktlint.sh",
        "$repoDir/tools/ci/requirements.txt",
        "$repoDir/.github/workflows/verify.yml",
        "$repoDir/.github/workflows/release.yml",
        "$repoDir/.github/dependabot.yml",
        "$repoDir/core/publicfeed/src/nativeInterop/h3c/include/h3api.h",
    ).withPropertyName("pinnedFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}

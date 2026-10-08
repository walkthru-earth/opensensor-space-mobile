import org.gradle.api.Project

/**
 * The root gates. `verify` is the host gate (Android lint and APK plus every host test) and
 * `verifyIos` is the iOS gate (simulator tests plus the framework link). The root build script
 * registers both tasks. Each convention plugin adds its own module tasks to them, so a module is
 * gated as soon as it applies a convention and no hand kept list can miss one.
 */
internal object Gates {
    const val HOST: String = "verify"
    const val IOS: String = "verifyIos"
}

/** Makes the root task [gate] depend on [taskNames] of this project. */
internal fun Project.addToGate(gate: String, vararg taskNames: String) {
    val paths = taskNames.map { "$path:$it" }
    rootProject.tasks.named { it == gate }.configureEach { dependsOn(paths) }
}

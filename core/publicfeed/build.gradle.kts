// The public Discover feed (docs/devices-and-discovery.md Part 2). `commonMain` holds the feed port,
// the Parquet feed reader (the portable :core:parquet reader, so Android and iOS share one path),
// the H3 interface, the viewport cover, the bundled sample source and the colour scale. `jvmMain`
// binds H3 to com.uber:h3. `iosMain` holds the iOS H3 binding.
plugins {
    id("opensensor.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:parquet"))
            // ByteBudgetLru and MemoryPressure bound the bundled sample cache (#61).
            api(project(":core:storage"))
            // WHO 2021 steps for PM2.5 and PM10 (CellScale, HealthAxis).
            api(project(":core:airquality"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.h3)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// H3 on iOS. The vendored H3 C library (src/nativeInterop/h3c, v4.5.0, the version inside
// com.uber:h3 4.5.0) is compiled per iOS target with xcrun clang into a static library, which
// cinterop binds and embeds in the klib, so the Xcode project needs no extra step.
val h3Source = layout.projectDirectory.dir("src/nativeInterop/h3c")
kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        val (sdk, triple) = when (konanTarget.name) {
            "ios_arm64" -> "iphoneos" to "arm64-apple-ios15.5"
            "ios_simulator_arm64" -> "iphonesimulator" to "arm64-apple-ios15.5-simulator"
            else -> error("no H3 build for $konanTarget")
        }
        val out = layout.buildDirectory.dir("h3c/$name")
        val buildH3 = tasks.register<Exec>("buildH3${name.replaceFirstChar { it.uppercase() }}") {
            inputs.dir(h3Source).withPropertyName("h3Source")
            outputs.dir(out).withPropertyName("h3Library")
            val outDir = out.get().asFile
            val src = h3Source.asFile
            doFirst { outDir.mkdirs() }
            commandLine(
                "sh",
                "-c",
                "set -e; for f in \"$src\"/lib/*.c; do " +
                    "xcrun --sdk $sdk clang -c -O2 -fPIC -target $triple -DH3_HAVE_VLA -I \"$src/include\" \"\$f\" -o \"$outDir/\$(basename \"\$f\" .c).o\"; " +
                    "done; rm -f \"$outDir/libh3.a\"; xcrun libtool -static -o \"$outDir/libh3.a\" \"$outDir\"/*.o",
            )
        }
        compilations.getByName("main").cinterops.create("h3c") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/h3c.def"))
            includeDirs(h3Source.dir("include").asFile)
            extraOpts("-libraryPath", out.get().asFile.absolutePath)
        }
        tasks.named("cinteropH3c${name.replaceFirstChar { it.uppercase() }}") { dependsOn(buildH3) }
    }
}

// The bundled sample feed lives once, as Compose resources of :feature:discover. The common tests
// read it from there on the JVM and in the simulator (which sees the host file system), so the files
// the app ships are the files the tests check. `SIMCTL_CHILD_` variables reach the simulator process
// without the prefix.
val sampleRoot = rootProject.file("feature/discover/src/commonMain/composeResources/files/discover-sample")
tasks.withType<Test>().configureEach {
    systemProperty("discover.sample.root", sampleRoot.absolutePath)
    inputs.dir(sampleRoot).withPropertyName("discoverSample")
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_DISCOVER_SAMPLE_ROOT", sampleRoot.absolutePath)
    inputs.dir(sampleRoot).withPropertyName("discoverSample")
}

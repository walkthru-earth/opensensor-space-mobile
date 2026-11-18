Vendored H3 core library v4.5.0 (https://github.com/uber/h3, Apache 2.0, see LICENSE), `src/h3lib`
only. `include/h3api.h` is `h3api.h.in` with the version filled in. Built for the iOS targets by
`core/publicfeed/build.gradle.kts` (xcrun clang into a static library) and bound through cinterop
(`../cinterop/h3c.def`). Android and the JVM use `com.uber:h3` at the `h3` version of `gradle/libs.versions.toml`, which wraps the H3 C library.

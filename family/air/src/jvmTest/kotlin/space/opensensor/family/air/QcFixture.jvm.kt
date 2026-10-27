package space.opensensor.family.air

private object Fixtures

/** Reads the fixture from the classpath, where `testFixtures(project(":core:parquet"))` puts it. */
actual fun fixtureBytes(path: String): ByteArray =
    checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

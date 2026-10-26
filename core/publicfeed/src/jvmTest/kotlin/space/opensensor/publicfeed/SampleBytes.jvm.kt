package space.opensensor.publicfeed

import java.io.File

actual fun sampleBytes(path: String): ByteArray =
    File(System.getProperty("discover.sample.root") ?: error("discover.sample.root is not set"), path).readBytes()

actual fun fixtureBytes(path: String): ByteArray =
    File(System.getProperty("publicfeed.fixture.root") ?: error("publicfeed.fixture.root is not set"), path).readBytes()

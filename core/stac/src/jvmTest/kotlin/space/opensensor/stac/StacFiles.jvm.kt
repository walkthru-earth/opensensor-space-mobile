package space.opensensor.stac

import java.io.File

actual fun stacFile(path: String): ByteArray =
    File(System.getProperty("stac.fixture.root") ?: error("stac.fixture.root is not set"), path).readBytes()

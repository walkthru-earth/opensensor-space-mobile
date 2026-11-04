package space.opensensor.stac

/**
 * The bytes of [path] under `src/commonTest/fixtures/stac-opensensor`, the files copied byte for byte
 * from stac-opensensor v0.1.0. The JVM and the iOS simulator both read them from the host file system.
 */
expect fun stacFile(path: String): ByteArray

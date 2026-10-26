package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetFileMetadata

/**
 * The `opensensor` footer of a raw file, or null when it has none the app can trust. Every footer
 * value the app reads (station, boot, schema, purpose, digests and cadence) comes from here. No
 * unprefixed footer key is read.
 */
val ParquetFileMetadata.footer: OpenSensorFooter? get() = OpenSensorFooter.of(keyValueMetadata)

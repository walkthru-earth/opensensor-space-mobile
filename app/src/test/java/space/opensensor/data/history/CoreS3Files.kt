package space.opensensor.data.history

import space.opensensor.model.BundledCatalog

/**
 * Reads every file as the bundled CoreS3 template. The device fixtures predate the bundled dict, so
 * the default [FileTemplates.BUNDLED] gives them rows and no metric values.
 */
val CoreS3Files: FileTemplates = checkNotNull(BundledCatalog.template("3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9")).let { t -> FileTemplates { _, _ -> t } }

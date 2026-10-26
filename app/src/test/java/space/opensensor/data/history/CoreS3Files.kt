package space.opensensor.data.history

import space.opensensor.model.BundledCatalog

/**
 * Reads every file as the bundled CoreS3 template. The device fixtures predate the bundled dict, so
 * the default [FileTemplates.BUNDLED] gives them rows and no metric values.
 */
val CoreS3Files: FileTemplates = BundledCatalog.templates.single { it.id.startsWith("cores3-") }.let { t -> FileTemplates { _, _ -> t } }

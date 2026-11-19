package space.opensensor.data.history

import space.opensensor.model.BundledCatalog

/**
 * Reads every file as the bundled CoreS3 template. The device fixtures predate the bundled dict, so
 * the default [FileTemplates.BUNDLED] gives them rows and no metric values.
 */
val CoreS3Files: FileTemplates = checkNotNull(BundledCatalog.template("f4f81218dc80d1a8e6db043197a2f7180c4f075f9e8348df30eaceef44d68370")).let { t -> FileTemplates { _, _ -> t } }

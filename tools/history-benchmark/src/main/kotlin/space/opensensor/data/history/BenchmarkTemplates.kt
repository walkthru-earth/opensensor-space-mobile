package space.opensensor.data.history

import space.opensensor.model.BundledCatalog

// LegacyHistoryPipeline reads the CoreS3 template that HistoryTestSupport declares for the tests.
// That file holds expect declarations a JVM module cannot compile, so the benchmark declares its own,
// picked by the same pinned dictionary.
internal val coreS3Template = checkNotNull(BundledCatalog.template(LiveFixtures.cores3Dict))

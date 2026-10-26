package space.opensensor.data.local.database

/** A fresh in-memory [AppDatabase] on the bundled driver, configured as the app configures it. */
expect fun inMemoryAppDatabase(): AppDatabase

/** The [AppDatabase] in the file at [path], configured as the app configures it. Missing parent directories are created. */
expect fun fileAppDatabase(path: String): AppDatabase

/** A new empty directory for one test. */
expect fun newTempDir(): String

/** Removes [dir] and everything below it. */
expect fun deleteTempDir(dir: String)
